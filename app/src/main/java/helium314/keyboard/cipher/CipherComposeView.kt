package helium314.keyboard.cipher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.TextView
import kotlin.math.abs

/**
 * The compose row, with a hand-drawn cursor.
 *
 * ## Why by hand
 *
 * An IME window does not take focus: if it did, it would take it from the
 * app's field, which is what the keyboard exists to fill. A `TextView` draws
 * the caret only when focused, and an `EditText` here would never be. So the
 * cursor in this row is not the system one: it is a bar drawn in [onDraw] at
 * the position the buffer reports.
 *
 * Not cosmetic: without it, the user does not know where the next character
 * will go after moving the cursor or deleting.
 *
 * ## Blinking
 *
 * Half a second on, half off, like any text field. Stopped when the row is not
 * visible or not attached: an `invalidate` every half second on a closed
 * keyboard is pure battery cost. Typing turns the caret **on and restarts the
 * phase**, so it is never off right when you look at where you are typing.
 *
 * ## Touch
 *
 * Tap = cursor there. Drag = scroll. Long press or double tap = the word under
 * the finger, adjustable with handles. The usual text field gestures, written
 * by hand for the same reason as the cursor: an unfocused `TextView` does not
 * do them by itself.
 */
class CipherComposeView(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {

    private val caretPaint = Paint().apply { isAntiAlias = false }
    private val selectionPaint = Paint().apply { isAntiAlias = false }

    private var selectionStart = 0
    private var selectionEnd = 0

    private var caretOn = true

    /**
     * Where the finger first touched: the selection extends **from there**, not
     * from where it is now.
     */
    private var ancora = 0

    private var trascinando = false

    private var xIniziale = 0f
    private var yIniziale = 0f

    /** True from the long press until release: the tap has already acted. */
    private var giaSelezionato = false

    /**
     * True from the first touch to release, whatever the gesture is doing.
     *
     * While the finger is down **the finger decides** what is in view, and
     * [setComposed] must not bring the cursor back into view. Selecting
     * backwards, `selectionEnd` is the **anchor** (the start point, further on in
     * the text), and bringing it into view on every move pulled the view back
     * while the user tried to read further up.
     */
    private var gestoInCorso = false

    /**
     * The word taken by the long press.
     *
     * Holding on produces more `ACTION_MOVE` events at the same point, and
     * without these the selection collapsed to a cursor right after opening. From
     * here dragging **widens**: it never shrinks below the word.
     */
    private var parolaInizio = -1
    private var parolaFine = -1

    /**
     * Selection listener. The view does not touch the buffer: it says where the
     * finger put the cursor, and the owner of the text decides. The buffer is the
     * only source of truth on where the next character goes.
     */
    var onSelezione: ((Int, Int) -> Unit)? = null

    /**
     * The finger lifted and a selection remains: time to offer what to do with
     * it. Receives the x where the finger left, so the menu appears from there.
     *
     * On release rather than during the drag: the selection changes with every
     * pixel, and a menu appearing mid-gesture gets tapped by mistake.
     */
    var onMenu: ((Float) -> Unit)? = null

    /** Closes the menu: the selection is gone, or no longer its own. */
    var onMenuDaChiudere: (() -> Unit)? = null

    /**
     * Long press on the EMPTY row, to offer "paste" (the toolbar paste key
     * disappears when the emoji panel is open).
     */
    var onMenuVuoto: ((Float) -> Unit)? = null

    /**
     * The finger touched the row, whatever it is about to do.
     *
     * Used to dismiss the decrypted message panel: touching the row means "I want
     * to reply", and the panel covers the keys.
     *
     * Fires **before** the early return for empty text below: an empty row is
     * exactly when one is about to reply.
     */
    var onToccata: (() -> Unit)? = null

    /**
     * What the finger is doing.
     *
     * ## Why dragging SCROLLS rather than selects
     *
     * In a box two or three lines tall, text below was unreachable if every drag
     * was a selection. Android's text field model applies here too: **dragging
     * scrolls**, selection is by long press or double tap, and the ends are
     * adjusted with handles. Inertia and edge scrolling while dragging a handle
     * follow from that.
     */
    private enum class Gesto { NESSUNO, ATTESA, SCORRIMENTO, MANIGLIA_INIZIO, MANIGLIA_FINE, ESTENDE }

    private var gesto = Gesto.NESSUNO
    private val scroller = OverScroller(context)
    private var velocita: VelocityTracker? = null
    private var yPrecedente = 0f

    /** The finger moved during this gesture. See the release of a handle. */
    private var mossoDurante = false
    private var ultimoRilascio = 0L
    private var xUltimoRilascio = 0f
    private var yUltimoRilascio = 0f

    /** Handles take the cursor colour: they are the same thing. */
    private val manigliePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun raggioManiglia(): Float = RAGGIO_MANIGLIA_DP * resources.displayMetrics.density

    /**
     * An offset that can be given to `Layout` without blowing up.
     *
     * `getPrimaryHorizontal` and `getLineForOffset` index the text and throw out
     * of range, and here throwing means **the keyboard disappearing while
     * typing** (`disegnaManiglie` runs inside `onDraw`).
     *
     * The selection ends belong to the view, but the buffer is cleared **from
     * outside** (`CipherCompose.clear()` after a successful send). Between that
     * write and the selection update the ends point past the end of the text.
     */
    private fun offsetValido(offset: Int): Int = offset.coerceIn(0, text?.length ?: 0)

    /** Centre of a selection end's handle, in view coordinates. */
    private fun centroManiglia(offset: Int): Pair<Float, Float>? {
        val l = layout ?: return null
        val sicuro = offsetValido(offset)
        val riga = l.getLineForOffset(sicuro)
        val x = l.getPrimaryHorizontal(sicuro) + totalPaddingLeft - scrollX
        val y = l.getLineBottom(riga).toFloat() + totalPaddingTop - scrollY + raggioManiglia()
        return x to y
    }

    private fun manigliaToccata(x: Float, y: Float): Gesto {
        if (selectionEnd <= selectionStart) return Gesto.NESSUNO
        // The target is twice the drawn size: a seven-point dot is easy to see and
        // hard to hit.
        val tolleranza = raggioManiglia() * 2f
        centroManiglia(selectionStart)?.let { (cx, cy) ->
            if (abs(x - cx) < tolleranza && abs(y - cy) < tolleranza) return Gesto.MANIGLIA_INIZIO
        }
        centroManiglia(selectionEnd)?.let { (cx, cy) ->
            if (abs(x - cx) < tolleranza && abs(y - cy) < tolleranza) return Gesto.MANIGLIA_FINE
        }
        return Gesto.NESSUNO
    }

    /** How far one can scroll in total, in pixels. */
    private fun scorrimentoMassimo(): Int {
        val l = layout ?: return 0
        val visibile = (if (height > 0) height else measuredHeight) -
            totalPaddingTop - totalPaddingBottom
        // One can scroll a little PAST the last line, enough for the handle, which
        // is drawn below the line: on the last line it was half outside the
        // scrollable content and impossible to grab.
        val perLaManiglia = (raggioManiglia() * 3f).toInt()
        return (l.height + perLaManiglia - visibile).coerceAtLeast(0)
    }

    /**
     * Finger-driven scrolling **disarms** the cursor-following scroll.
     *
     * [applicaScorrimento] is an invariant reapplied on every draw: without this,
     * every drag, fling or edge scroll was undone on the next frame.
     */
    private fun scorriAMano(y: Int) {
        daPortareInVista = -1
        scrollTo(0, y)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scorriAMano(scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    /**
     * The two dots at the ends of the selection, to **adjust** a selection
     * without redoing it.
     */
    private fun disegnaManiglie(canvas: Canvas, left: Float, top: Float) {
        if (selectionEnd <= selectionStart) return
        val l = layout ?: return
        val raggio = raggioManiglia()
        for (grezzo in intArrayOf(selectionStart, selectionEnd)) {
            val offset = offsetValido(grezzo)
            val riga = l.getLineForOffset(offset)
            canvas.drawCircle(
                left + l.getPrimaryHorizontal(offset),
                top + l.getLineBottom(riga) + raggio,
                raggio,
                manigliePaint,
            )
        }
    }

    /** Last finger position, for edge scrolling. */
    private var xCorrente = 0f
    private var yCorrente = 0f

    /**
     * Scrolls by one line while the finger is at the edge, and extends the
     * selection to where it got.
     *
     * Standard Android text selection behaviour: a selection only reaches where
     * the finger reaches, so without it text outside the window could not be
     * selected.
     */
    private val scorrimentoAlBordo = object : Runnable {
        override fun run() {
            if (gesto == Gesto.NESSUNO || gesto == Gesto.SCORRIMENTO) return
            val margine = MARGINE_BORDO_DP * resources.displayMetrics.density
            val giu = yCorrente > height - margine
            val su = yCorrente < margine
            if (!giu && !su) {
                // The finger is not at the edge NOW, but the gesture is still in
                // progress: stay armed. Otherwise pausing away from the edge
                // stopped the scrolling for good.
                postDelayed(this, INTERVALLO_BORDO_MS)
                return
            }

            val passo = lineHeight.coerceAtLeast(1)
            val nuovo = if (giu) {
                (scrollY + passo).coerceAtMost(scorrimentoMassimo())
            } else {
                (scrollY - passo).coerceAtLeast(0)
            }
            if (nuovo != scrollY) {
                scorriAMano(nuovo)
                // The selection follows: what is under the finger changed, not the
                // finger.
                val offset = getOffsetForPosition(xCorrente, yCorrente)
                    .coerceIn(0, text?.length ?: 0)
                when (gesto) {
                    Gesto.MANIGLIA_INIZIO ->
                        onSelezione?.invoke(minOf(offset, parolaFine), maxOf(offset, parolaFine))
                    Gesto.MANIGLIA_FINE ->
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaInizio, offset))
                    Gesto.ESTENDE ->
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaFine, offset))
                    else -> {}
                }
            }
            postDelayed(this, INTERVALLO_BORDO_MS)
        }
    }

    private val menuVuoto = Runnable { onMenuVuoto?.invoke(xIniziale) }

    private val pressioneLunga = Runnable {
        val (inizio, fine) = parolaIntorno(ancora)
        if (fine > inizio) {
            gesto = Gesto.ESTENDE
            giaSelezionato = true
            parolaInizio = inizio
            parolaFine = fine
            onSelezione?.invoke(inizio, fine)
            invalidate()
        }
    }

    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MILLIS)
        }
    }

    /**
     * The text colour, not an accent: HeliBoard's palette does not expose one,
     * and an invented colour would be the only element not following the user's
     * theme.
     */
    fun setCaretColor(color: Int) {
        caretPaint.color = color
        // The selection is the same colour, very transparent: text must stay
        // readable under it.
        selectionPaint.color = (color and 0x00FFFFFF) or (SELECTION_ALPHA shl 24)
        // Handles are the cursor in another shape: same solid colour.
        manigliePaint.color = color
        invalidate()
    }

    /** Text and cursor position, from the buffer. */
    fun setComposed(text: CharSequence, start: Int, end: Int) {
        setText(text)
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(selectionStart, text.length)
        caretOn = true
        restartBlink()
        // While the finger is down it is in charge: see [gestoInCorso].
        if (!gestoInCorso) scrollToCaret()
        invalidate()
    }

    /**
     * The gestures.
     *
     * **Everything** is consumed from the first touch on: otherwise the rest of
     * the sequence would go to whatever is below, and a drag would start here and
     * end elsewhere.
     *
     * `requestDisallowInterceptTouchEvent` because the row sits inside the
     * suggestion strip, which intercepts horizontal moves to scroll.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onToccata?.invoke()
        if (text.isNullOrEmpty()) {
            // Empty row: the only meaningful gesture is a long press to paste.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xIniziale = event.x
                    postDelayed(menuVuoto, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(event.x - xIniziale) >
                        ViewConfiguration.get(context).scaledTouchSlop
                    ) {
                        removeCallbacks(menuVuoto)
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(menuVuoto)
            }
            return true
        }
        val offset = offsetDelTocco(event)
        if (velocita == null) velocita = VelocityTracker.obtain()
        velocita?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                gestoInCorso = true
                xIniziale = event.x
                yIniziale = event.y
                xCorrente = event.x
                yCorrente = event.y
                yPrecedente = event.y
                ancora = offset
                trascinando = false
                giaSelezionato = false

                // A handle has priority: starting there means adjusting an existing
                // selection.
                val maniglia = manigliaToccata(event.x, event.y)
                if (maniglia != Gesto.NESSUNO) {
                    gesto = maniglia
                    mossoDurante = false
                    parolaInizio = selectionStart
                    parolaFine = selectionEnd
                    return true
                }

                // A new touch cancels the previous one: a menu left open would refer
                // to a selection about to change.
                onMenuDaChiudere?.invoke()

                // Double tap: selects the word, then dragging widens it.
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val doppio = event.eventTime - ultimoRilascio <=
                    ViewConfiguration.getDoubleTapTimeout() &&
                    abs(event.x - xUltimoRilascio) < slop &&
                    abs(event.y - yUltimoRilascio) < slop
                if (doppio) {
                    val (i, f) = parolaIntorno(offset)
                    if (f > i) {
                        gesto = Gesto.ESTENDE
                        giaSelezionato = true
                        parolaInizio = i
                        parolaFine = f
                        onSelezione?.invoke(i, f)
                        invalidate()
                        return true
                    }
                }

                gesto = Gesto.ATTESA
                parolaInizio = -1
                parolaFine = -1
                postDelayed(pressioneLunga, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                xCorrente = event.x
                yCorrente = event.y
                mossoDurante = true
                if (gesto == Gesto.ATTESA) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(event.x - xIniziale) > slop || abs(event.y - yIniziale) > slop) {
                        // The finger moved without selecting anything: it wants to
                        // read, not choose. Cancel the long press.
                        removeCallbacks(pressioneLunga)
                        gesto = Gesto.SCORRIMENTO
                        trascinando = true
                    }
                }
                when (gesto) {
                    Gesto.SCORRIMENTO -> {
                        val dy = (yPrecedente - event.y).toInt()
                        val nuovo = (scrollY + dy).coerceIn(0, scorrimentoMassimo())
                        if (nuovo != scrollY) scorriAMano(nuovo)
                        yPrecedente = event.y
                    }
                    Gesto.MANIGLIA_INIZIO -> {
                        onSelezione?.invoke(minOf(offset, parolaFine), maxOf(offset, parolaFine))
                        avviaScorrimentoAlBordo()
                    }
                    Gesto.MANIGLIA_FINE -> {
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaInizio, offset))
                        avviaScorrimentoAlBordo()
                    }
                    Gesto.ESTENDE -> {
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaFine, offset))
                        avviaScorrimentoAlBordo()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(pressioneLunga)
                removeCallbacks(scorrimentoAlBordo)
                when (gesto) {
                    // Plain tap: the cursor goes there.
                    Gesto.ATTESA -> onSelezione?.invoke(offset, offset)
                    Gesto.SCORRIMENTO -> lanciaInerzia()
                    // A handle touched and released WITHOUT moving is a tap, and a
                    // tap moves the cursor. Tapping elsewhere to leave a selection
                    // often lands on a handle, and the cursor would otherwise stay
                    // put while the menu reopened.
                    Gesto.MANIGLIA_INIZIO, Gesto.MANIGLIA_FINE ->
                        if (!mossoDurante) {
                            onSelezione?.invoke(offset, offset)
                            onMenuDaChiudere?.invoke()
                        } else if (selectionEnd > selectionStart) {
                            onMenu?.invoke(event.x)
                        }
                    // A gesture that produced a selection opens the menu.
                    else -> if (selectionEnd > selectionStart) onMenu?.invoke(event.x)
                }
                ultimoRilascio = event.eventTime
                xUltimoRilascio = event.x
                yUltimoRilascio = event.y
                chiudiGesto()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pressioneLunga)
                removeCallbacks(scorrimentoAlBordo)
                chiudiGesto()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun avviaScorrimentoAlBordo() {
        // Re-armed on every move and stopped when the finger comes back in: no
        // "already scrolling" state to keep in sync.
        removeCallbacks(scorrimentoAlBordo)
        post(scorrimentoAlBordo)
    }

    private fun lanciaInerzia() {
        velocita?.computeCurrentVelocity(1000)
        val vy = velocita?.yVelocity ?: 0f
        if (abs(vy) <= ViewConfiguration.get(context).scaledMinimumFlingVelocity) return
        scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, scorrimentoMassimo())
        postInvalidateOnAnimation()
    }

    private fun chiudiGesto() {
        gesto = Gesto.NESSUNO
        gestoInCorso = false
        trascinando = false
        giaSelezionato = false
        velocita?.recycle()
        velocita = null
    }

    private fun offsetDelTocco(event: MotionEvent): Int {
        val posizione = getOffsetForPosition(event.x, event.y)
        return if (posizione < 0) text.length else posizione.coerceIn(0, text.length)
    }

    /**
     * The word around a point, for the long press.
     *
     * "Word" means a run of letters and digits: what a user expects to see
     * highlighted. On a space or punctuation nothing is selected.
     */
    private fun parolaIntorno(posizione: Int): Pair<Int, Int> {
        val testo = text ?: return 0 to 0
        if (testo.isEmpty()) return 0 to 0
        // Between two characters the one on the left counts, as for the cursor.
        val dentro = (if (posizione >= testo.length) testo.length - 1 else posizione)
            .coerceAtLeast(0)
        if (!testo[dentro].isLetterOrDigit()) return 0 to 0
        var inizio = dentro
        while (inizio > 0 && testo[inizio - 1].isLetterOrDigit()) inizio--
        var fine = dentro
        while (fine < testo.length && testo[fine].isLetterOrDigit()) fine++
        return inizio to fine
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blink)
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) restartBlink() else removeCallbacks(blink)
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        if (!isShown) return
        caretOn = true
        postDelayed(blink, BLINK_MILLIS)
    }

    /**
     * The offset one line up or down, at the same horizontal position.
     *
     * For cursor up/down; the column x is kept, as in any text field.
     */
    fun offsetDiRiga(offset: Int, delta: Int): Int {
        val l = layout ?: return offset
        val lunghezza = text?.length ?: 0
        val corrente = l.getLineForOffset(offset.coerceIn(0, lunghezza))
        val destinazione = corrente + delta
        if (destinazione < 0) return 0
        if (destinazione >= l.lineCount) return lunghezza
        val x = l.getPrimaryHorizontal(offset.coerceIn(0, lunghezza))
        return l.getOffsetForHorizontal(destinazione, x).coerceIn(0, lunghezza)
    }

    /**
     * Brings the line containing the cursor into view.
     *
     * Not the last line of the text: someone who moved the cursor back is looking
     * **there**, and chasing the end of the text would take them away from it.
     */
    private fun scrollToCaret() = portaInVista(selectionEnd)

    /**
     * Position that must stay in view, waiting for a layout to compute it on. `-1`
     * when nothing is pending.
     */
    private var daPortareInVista = -1

    /**
     * ## Why not in `post`
     *
     * `setText` **resets scrolling**. Restoring it in a `post` lets one frame be
     * drawn at scroll zero before fixing it: the whole text jumped up and down on
     * every keystroke. Here it is applied immediately: after `setText` the layout
     * already exists (the width is fixed, so `TextView` rebuilds it in the same
     * pass) and scrolling is correct **before** drawing.
     *
     * If the layout does not exist yet, or something resets it later in the
     * pass, the position stays stored and [onPreDraw] reapplies it.
     */
    private fun portaInVista(posizione: Int) {
        daPortareInVista = posizione.coerceAtLeast(0)
        applicaScorrimento()
    }

    /**
     * Brings [daPortareInVista] into view, if needed.
     *
     * Does not consume the request: it is an **invariant**, not an event.
     * `TextView` resets scrolling several times per pass (one per measure pass,
     * and inside a weighted `LinearLayout` the child is measured twice).
     */
    private fun applicaScorrimento() {
        val posizione = daPortareInVista
        if (posizione < 0) return
        val l = layout ?: return
        // During measure the final height is not assigned yet.
        val altezza = if (height > 0) height else measuredHeight
        val visible = altezza - totalPaddingTop - totalPaddingBottom
        if (visible <= 0) return
        val line = l.getLineForOffset(posizione.coerceIn(0, text?.length ?: 0))
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        val y = when {
            bottom > scrollY + visible -> bottom - visible
            top < scrollY -> top
            else -> return
        }
        scrollTo(0, y.coerceAtLeast(0))
    }

    /**
     * The last word on scrolling, and it must be said here.
     *
     * `TextView` registers itself as an `OnPreDrawListener` when relaying out,
     * and its `onPreDraw` calls `bringTextIntoView`, which for an unfocused view
     * means `scrollTo(0, 0)`. It runs **after** measure and layout and **right
     * before** drawing, so any earlier correction is erased here.
     */
    override fun onPreDraw(): Boolean {
        val esito = super.onPreDraw()
        applicaScorrimento()
        return esito
    }

    /**
     * ## No `scrollX`/`scrollY` in here
     *
     * The canvas reaches `onDraw` **already translated** by `-scrollX,-scrollY`
     * (`View` does it before calling us), so drawing here is in content
     * coordinates, the same `Layout.getLineTop` returns. Subtracting them would
     * count scrolling twice.
     *
     * `totalPaddingTop` is right even for short text: it already includes the
     * `gravity="center_vertical"` offset.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val left = totalPaddingLeft
        val top = totalPaddingTop

        if (selectionEnd > selectionStart) {
            drawSelection(canvas, left.toFloat(), top.toFloat())
        }
        if (!caretOn) return

        // As for the handles: the ends go through `offsetValido` before touching
        // `Layout`. We are inside `onDraw`, where an exception closes the keyboard.
        val fine = offsetValido(selectionEnd)
        val line = l.getLineForOffset(fine)
        val x = left + l.getPrimaryHorizontal(fine)
        canvas.drawRect(
            x,
            (top + l.getLineTop(line)).toFloat(),
            x + CARET_WIDTH_PX * resources.displayMetrics.density,
            (top + l.getLineBottom(line)).toFloat(),
            caretPaint,
        )
    }

    /**
     * A selection can span several lines, and then it is not a rectangle: it is
     * drawn line by line, the middle ones full width.
     */
    private fun drawSelection(canvas: Canvas, left: Float, top: Float) {
        val l = layout ?: return
        val inizio = offsetValido(selectionStart)
        val fine = offsetValido(selectionEnd)
        val firstLine = l.getLineForOffset(inizio)
        val lastLine = l.getLineForOffset(fine)
        for (line in firstLine..lastLine) {
            val startX = if (line == firstLine) l.getPrimaryHorizontal(inizio) else l.getLineLeft(line)
            val endX = if (line == lastLine) l.getPrimaryHorizontal(fine) else l.getLineRight(line)
            canvas.drawRect(
                left + startX,
                top + l.getLineTop(line),
                left + endX,
                top + l.getLineBottom(line),
                selectionPaint,
            )
        }
        disegnaManiglie(canvas, left, top)
    }

    private companion object {
        const val BLINK_MILLIS = 500L
        const val CARET_WIDTH_PX = 2f
        const val SELECTION_ALPHA = 0x40

        /** Radius of the dot dragged to adjust the selection. */
        const val RAGGIO_MANIGLIA_DP = 7f

        /** How close to the edge the finger must be to scroll. */
        const val MARGINE_BORDO_DP = 16f

        /** One step every so often: faster would scroll too quickly. */
        const val INTERVALLO_BORDO_MS = 60L
    }
}
