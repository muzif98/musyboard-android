package helium314.keyboard.cipher

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.prefs

/**
 * The keyboard's two gestures: encrypt the field, send the field to be
 * decrypted.
 *
 * In the `cipher` package rather than `latin` so the fork can merge from
 * upstream without conflicts: upstream never touches this package. The price
 * is two lines in `KeyboardActionListenerImpl`, the only place where HeliBoard
 * knows this exists.
 */
object CipherActions {

    /**
     * Read limit for the field. A cap is needed (`InputConnection` goes over
     * Binder, and a transaction that is too large fails), but a cap used to
     * truncate silently would mean encrypting half a message and sending it.
     * So if the field reaches the limit, it is refused and the user is told.
     */
    private const val MAX_FIELD_CHARS = 8192

    /**
     * How long the blob can be for a chat to accept it.
     *
     * Telegram stops at 4096 characters per message, the tightest among the
     * apps that matter. Above that the message is not sent, or the app splits
     * it, and **a split blob no longer decrypts**: the half that arrives is not
     * a shorter message, it is garbage.
     *
     * Checked BEFORE replacing the field: noticing afterwards would mean having
     * already deleted the user's text to replace it with something that cannot
     * be sent.
     */
    private const val MAX_BLOB_CHARS = 4096

    /** Preference key prefix for the limit learned per app. */
    private const val PREF_LIMITE = "cipher_limite_campo_"

    /** See [imparaLimite]. */
    private const val LIMITE_MINIMO = 256

    /**
     * Blob envelope in bytes, counted on the real format: 4 of prefix, 32 of
     * header key, 24 of nonce, 16 of tag, plus 8 of timestamp and 32 of prekey
     * inside the ciphertext.
     *
     * Must err on the side of EXCESS, never short: refusing a message that
     * would have fitted is visible immediately, sending one that gets split is
     * not.
     */
    private const val OVERHEAD_BYTES = 116

    /**
     * Envelope of a GROUP message, without slots: 4 of prefix, 32 of ephemeral
     * key, 24 of nonce, 1 of count, 16 of tag and 8 of timestamp. No prekey
     * inside the ciphertext (a group has no forward secrecy).
     */
    private const val OVERHEAD_GRUPPO_BYTES = 85

    /**
     * One slot: 32 bytes of wrapped content key plus 16 of tag.
     *
     * **Without counting them the cap does not protect.** A group of eight
     * carries 432 bytes of slots alone; leaving them out made a message that
     * seemed to fit produce a blob over Telegram's 4096, after the row had
     * already been cleared.
     */
    private const val SLOT_BYTES = 48

    /**
     * `kc/`, three characters. The version is NOT in it: the core keeps it out
     * on purpose, so a blob from a future version stays recognisable as ours.
     */
    private const val SENTINEL_LEN = 3

    /**
     * How long to wait before treating the screen launch as refused.
     *
     * The process is already alive (it is the keyboard's), so the activity
     * shows up in a few tens of milliseconds. One second is ample: erring long
     * costs a late fallback, erring short costs a needless fallback on top of a
     * screen already open.
     */
    private const val ATTESA_APERTURA_MS = 1000L

    /** How long the keyboard window is given to appear. */
    private const val ATTESA_FINESTRA_MS = 1500L

    /**
     * How long an opening whose window did not arrive in time stays valid. See
     * [attendiFinestra]: beyond this, whoever opens the keyboard is no longer
     * waiting for that message, and showing it would be a surprise.
     */
    private const val ATTESA_TARDIVA_MS = 10_000L

    /**
     * How long to wait after a send before checking whether the field emptied,
     * i.e. whether the message really left.
     *
     * Erring long makes the next part appear with a visible delay; erring short
     * makes it not appear at all, because the field was still full. Half a
     * second is comfortable on the apps that matter.
     */
    private const val ATTESA_INVIO_MS = 500L

    /**
     * Replaces the content of the field with the encrypted blob.
     *
     * The recipient is NOT guessed: the core decides it per app (last sender
     * read, or remembered per package). If there is none, the user is asked:
     * encrypting for the wrong person is the worst failure this system can
     * produce.
     */
    fun encrypt(ime: InputMethodService) {
        val limite = limiteBlob(ime)
        cifraEConsegna(ime)
        // The field truncated the blob: [rimediaAlTroncamento] removed it,
        // learned how much really fits, and the text is still where it was.
        // Redo everything once with the new limit, which now splits into parts
        // what seemed to fit whole. Once only, because the learned limit can
        // only decrease: if even that is not enough, the second pass says so.
        if (limiteBlob(ime) < limite) cifraEConsegna(ime)
    }

    private fun cifraEConsegna(ime: InputMethodService) {
        if (!ready(ime)) return
        // A draft left over from another field of the same app: first choose
        // whether it is for this chat. See CipherCompose.inSospeso.
        if (CipherCompose.inSospeso()) {
            toast(ime, R.string.cipher_pending_choose)
            return
        }
        // A part is waiting for this field: the key delivers it instead of
        // starting a new message. This is the fallback (normally the next part
        // arrives by itself after sending) for when the send did not go through
        // here: the app's own send button, auto-send off, a chat reopened later.
        // [consegnaProssimaParte] still requires an empty field, so pressing the
        // lock on freshly written text encrypts that text.
        if (consegnaProssimaParte(ime)) return
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        // A chosen group counts as recipient: without this check the branch
        // below would ask for a single one, a question already answered.
        val gruppo = if (pacchetto.isEmpty()) null else CipherGroups.corrente(ime, pacchetto)
        if (gruppo == null && pacchetto.isNotEmpty() && !CipherCore.nativeHasCurrentPeer(pacchetto)) {
            chiediDestinatario(ime)
            return
        }
        val ic = appConnection(ime) ?: return

        // With the compose row active the plaintext was never in the app's
        // field: it is in the keyboard's buffer, and the field must be filled,
        // not replaced. If the buffer is empty the field is checked anyway: it
        // may hold text written before the mode was turned on.
        val composed = if (CipherCompose.isEnabled() && !CipherCompose.isEmpty()) {
            Field(CipherCompose.text(), 0, 0)
        } else {
            null
        }
        val field = composed ?: readField(ime, ic) ?: return
        if (field.text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_encrypt)
            return
        }

        // From here on the plaintext also exists as a String, which is
        // immutable and cannot be zeroized: the core's guarantee stops at
        // InputConnection, which speaks CharSequence. The array is zeroized
        // anyway, being the longest-lived copy.
        // The limit is reported before encrypting, with how much to cut;
        // afterwards the field would already have been cleared.
        // For a group the slots count too, the sender being one of them. If the
        // user added themselves as a member, the core removes the duplicate and
        // there will be one slot fewer: erring by one in excess is fine.
        val slot = if (gruppo != null) gruppo.membri.size + 1 else 0
        val limite = limiteBlob(ime)
        val stimato = stimaBlob(field.text, slot)
        if (stimato > limite) {
            // Does not fit in one message: split instead of refusing. Refusing
            // was correct and useless (nobody rewrites a long message shorter,
            // and with the compose row on that text lives only in our buffer).
            // The same estimate that decided the refusal now drives the split.
            // Ask BEFORE splitting whether this field can hold a queue: without
            // an identity for the field, parts from the second on would have
            // nowhere to go back to.
            val parti = if (CipherParti.campoUtilizzabile(ime.currentInputEditorInfo)) {
                CipherParti.dividi(field.text) { stimaBlob(it, slot) <= limite }
            } else {
                null
            }
            if (parti == null) {
                // Not even when splitting: the refusal stays, with how much to
                // cut.
                val daTogliere = ((stimato - limite) * 5 / 8).coerceAtLeast(1)
                toast(ime, R.string.cipher_message_too_long)
                KeyboardSwitcher.getInstance().showToast(
                    ime.getString(R.string.cipher_message_too_long_detail, daTogliere),
                    false,
                )
                return
            }
            spezzaECifra(ime, ic, field, composed != null, gruppo, parti)
            return
        }

        // From here on it is a new message that fits in one piece: if a queue
        // was still pending (parts never sent, and the user started over), it
        // dies now. Keeping it would make the next send put a piece of an
        // abandoned message in the field.
        CipherParti.scarta()
        CipherParti.salva(ime)
        val blob = cifraUno(ime, gruppo, field.text) ?: return
        if (gruppo != null) {
            consegna(ime, ic, field, composed != null, blob)
            return
        }
        // To disk IMMEDIATELY, before the blob leaves. With forward secrecy on,
        // encrypting is no longer read-only: it generates a new temporary key,
        // and if the process dies before it is saved, the reply arrives
        // encrypted to a key that no longer exists.
        // The outcome is checked: if it does not reach the disk, the message
        // would leave but the reply would never open, a failure that shows up
        // hours later on the other person's side. The field has not been
        // touched yet, so cancel instead of sending.
        if (!CipherIdentity.persistKeyring(ime)) {
            toast(ime, R.string.cipher_keyring_not_saved_send)
            return
        }
        consegna(ime, ic, field, composed != null, blob)
    }

    /**
     * Encrypts a single text, for a group or for the app's recipient.
     *
     * `null` when it could not, **with the notice already shown**: the caller
     * just stops.
     *
     * The plaintext array leaves here zeroized in every case: it is the only
     * copy this layer controls, and an early `return` must not skip it.
     */
    private fun cifraUno(
        ime: InputMethodService,
        gruppo: CipherGroup?,
        testo: String,
    ): String? {
        val plaintext = testo.toByteArray()
        // A group is encrypted its own way: one content key and one slot per
        // person. **No forward secrecy**: the switch is not even consulted (see
        // decision K1: a group has the forward secrecy of its weakest member,
        // and promising it would be worse than not having it).
        if (gruppo != null) {
            val chiavi = ByteArray(gruppo.membri.size * 32)
            for ((i, membro) in gruppo.membri.withIndex()) {
                membro.copyInto(chiavi, i * 32)
            }
            // Starts from INTERNAL, not zero: `IntArray(1)` is zero-filled, and
            // zero means OK in the bridge. A path that does not write the reason
            // would otherwise leave "succeeded" next to a null result.
            val motivo = intArrayOf(CipherCore.INTERNAL)
            val blobGruppo = try {
                CipherCore.nativeEncryptGroup(
                    chiavi, plaintext, System.currentTimeMillis() / 1000, motivo,
                )
            } finally {
                plaintext.fill(0)
            }
            if (blobGruppo == null) {
                // A member forgotten after the group was saved is the one failure
                // the user can fix, so it gets its own message.
                toast(
                    ime,
                    if (motivo[0] == CipherCore.UNKNOWN_PEER) R.string.cipher_group_member_gone
                    else R.string.cipher_group_failed,
                )
            }
            return blobGruppo
        }
        val blob = try {
            CipherCore.nativeEncryptForApp(
                ime.currentInputEditorInfo?.packageName.orEmpty(),
                plaintext,
                System.currentTimeMillis() / 1000,
                CipherSettings.isForwardSecrecy(ime),
            )
        } finally {
            plaintext.fill(0)
        }
        if (blob == null) {
            // A dead end becomes the entry point: asking "to whom?" without a
            // way to answer made the key look broken.
            chiediDestinatario(ime)
        }
        return blob
    }

    /**
     * The message does not fit in one blob: split it, encrypt each part on its
     * own, deliver the first and queue the others.
     *
     * ## The order is everything
     *
     * **Everything** is encrypted first, then the field is touched. Discovering
     * at the third part that something is wrong, with the first two already
     * sent, would mean half a message sent with no way to withdraw it. If any
     * part fails to encrypt, nothing has happened and the text is still where
     * it was.
     *
     * The keyring is saved once, after all of them: with forward secrecy on,
     * every part generated a temporary key, all in the same in-memory store.
     */
    private fun spezzaECifra(
        ime: InputMethodService,
        ic: InputConnection,
        field: Field,
        dallaRiga: Boolean,
        gruppo: CipherGroup?,
        parti: List<String>,
    ) {
        val blob = ArrayList<String>(parti.size)
        for (parte in parti) {
            blob.add(cifraUno(ime, gruppo, parte) ?: return)
        }
        // Groups have no new keys to save (no forward secrecy), but the check
        // is cheap and applies to both paths.
        if (!CipherIdentity.persistKeyring(ime)) {
            toast(ime, R.string.cipher_keyring_not_saved_send)
            return
        }
        // The queue is taken **after** the first part reached the field.
        // Otherwise a failed delivery would leave parts two onwards pending, and
        // the next gesture would deliver the second in place of the first.
        if (!consegna(ime, ic, field, dallaRiga, blob.first())) return
        CipherParti.accoda(ime.currentInputEditorInfo, blob.drop(1), blob.size, dallaRiga)
        CipherParti.consegnata(blob.first())
        CipherCompose.segnaCifrato()
        // To disk immediately: from here the plaintext no longer exists
        // anywhere (delivery cleared the row) and these parts are the only copy
        // of what remains to be sent.
        CipherParti.salva(ime)
        annunciaParte(ime, 1, blob.size)
        // The first part may already have left by itself if auto-send is on:
        // then the second follows without waiting for a gesture. The queue did
        // not exist yet when [consegna] tried.
        dopoInvio(ime)
    }

    /**
     * Delivers the next part, if there is one for this field and the field is
     * empty.
     *
     * **An empty field is the condition, not a detail.** It is the only signal
     * a keyboard has that the previous message left: if there is still text,
     * either the send did not happen or the user started typing again, and
     * writing over it would destroy something. The part stays queued and is
     * retried next time.
     */
    private fun consegnaProssimaParte(ime: InputMethodService): Boolean {
        val campo = ime.currentInputEditorInfo
        if (!CipherParti.inAttesaSu(campo)) return false
        if (!CipherCompose.isEmptyBuffer()) return false
        val ic = appConnection(ime) ?: return false
        if (!fieldIs(ic, "")) return false
        val (quale, quante) = CipherParti.prossimaEtichetta()
        val blob = CipherParti.prossima(campo) ?: return false
        ic.beginBatchEdit()
        ic.finishComposingText()
        val consegnato = ic.commitText(blob, 1)
        ic.endBatchEdit()
        if (!consegnato || !fieldIs(ic, blob)) {
            // A truncated piece in the field would be sent with one tap and never
            // open: remove it. The queue stays as it is.
            rimediaAlTroncamento(ime, ic, blob, "")
            // The field did not take it, so that blob did not leave: **the
            // queue stays whole**. Say what happened and leave the way to retry
            // (the lock key, which delivers the pending part).
            toast(ime, R.string.cipher_part_failed)
            return false
        }
        CipherParti.consuma()
        CipherParti.consegnata(blob)
        CipherParti.salva(ime)
        annunciaParte(ime, quale, quante)
        // Auto-send applies only if the first part came from the row: in field
        // mode the fork never sends by itself, and starting from the second
        // part would change the sending mode halfway through a message.
        if (CipherParti.daRiga()) {
            deliver(ime, ic, blob)
            // And if it sent, the next part follows: without this line the chain
            // stopped at the second part, because nobody scheduled the third.
            dopoInvio(ime)
        }
        return true
    }

    /**
     * The app's field changed: if it emptied, the message left and the next
     * part is due.
     *
     * **This is the main path, not the enter key.** In some apps you send by
     * tapping the app's send button, which the keyboard does not see: hooking
     * only ENTER meant part 2 never arrived by itself in the way people
     * actually send. `onUpdateSelection` arrives whichever way the send went.
     *
     * Costs a comparison of two integers when there is no queue, i.e. always
     * except during a split message.
     */
    fun campoAggiornato(
        ime: InputMethodService,
        vecchioInizio: Int,
        vecchiaFine: Int,
        nuovoInizio: Int,
        nuovaFine: Int,
    ) {
        if (nuovoInizio != 0 || nuovaFine != 0) return
        if (!CipherParti.inAttesaSu(ime.currentInputEditorInfo)) return
        // Emptied **by a send**, not by hand: the cursor was at the end of the
        // blob and the field went empty in one step. Deleting by hand gets there
        // from another position, and putting the next part back would not let
        // the user discard it. Whoever wants to continue anyway has the lock.
        val lunghezza = CipherParti.ultimaLunghezza()
        if (lunghezza == 0 || vecchioInizio != lunghezza || vecchiaFine != lunghezza) return
        consegnaProssimaParte(ime)
    }

    /** Which part just landed in the field, and how many there are. */
    private fun annunciaParte(ime: InputMethodService, quale: Int, quante: Int) {
        KeyboardSwitcher.getInstance().showToast(
            ime.getString(R.string.cipher_part_ready, quale, quante),
            false,
        )
    }

    /**
     * After a send, tries to deliver the next part.
     *
     * The delay is not superstition: the app performs the send, and the field
     * empties when it is done. Checking immediately would find the field still
     * full ("nothing left") and the queue would wait for the next gesture.
     */
    private fun dopoInvio(ime: InputMethodService) {
        if (!CipherParti.inAttesaSu(ime.currentInputEditorInfo)) return
        Handler(Looper.getMainLooper()).postDelayed({
            consegnaProssimaParte(ime)
        }, ATTESA_INVIO_MS)
    }

    /**
     * Puts the blob where it belongs and asks the app to send it.
     *
     * Shared by the paths that encrypt differently but deliver the same way, so
     * there is one place that refuses to clear the row before verifying that
     * the blob arrived.
     */
    private fun consegna(
        ime: InputMethodService,
        ic: InputConnection,
        field: Field,
        dallaRiga: Boolean,
        blob: String,
    ): Boolean {
        if (dallaRiga) {
            // The app's field is empty by construction: nothing is replaced,
            // it is delivered. The buffer is cleared only AFTER the blob was
            // delivered; clearing it first, with delivery then failing, would
            // delete a message that never left.
            //
            // "After" in time was not enough: the return value of `commitText`
            // was ignored and `clear()` ran anyway. With a dead connection, or a
            // field truncating at a maximum length, the plaintext vanished from
            // the only place it existed and reached nobody.
            //
            // So the field is READ BACK, as in [replaceField]: a `commitText`
            // can say yes and leave something different in the field.
            when (inserisciNelCampo(ime, ic, blob)) {
                Inserimento.RIUSCITO -> Unit
                // The field is back as it was and the limit is learned:
                // [encrypt] goes round again splitting into parts.
                Inserimento.TRONCATO -> {
                    avvisaLimiteBlob(ime)
                    return false
                }
                Inserimento.FALLITO -> {
                    // Nothing is cleared and nothing is sent: the message stays
                    // in the row where the user sees it. A send to repeat beats
                    // a message to rewrite.
                    toast(ime, R.string.cipher_send_failed_kept)
                    return false
                }
            }
            // The WHOLE field, not just the blob: if something was already
            // there, it is the field as it is now that must not be taken back
            // and from which the simulated enter's newline is removed.
            val campo = testoDelCampo(ic) ?: blob
            Consegnato.segna(campo)
            CipherCompose.clear()
            deliver(ime, ic, campo)
            // If auto-send sent and there is a queue, the next part goes by
            // itself. If not, the field is still full and this does nothing: the
            // queue waits for a manual send.
            dopoInvio(ime)
            return true
        }
        if (!replaceField(ic, field, blob)) {
            // Truncated: the plaintext had already been removed to make room for
            // the blob, and must be put back (the field was its only home).
            if (rimediaAlTroncamento(ime, ic, blob, field.text)) return false
            // The field was not replaced: the user must know, because a silent
            // failure here is the worst possible (believing it was encrypted and
            // sending the plaintext).
            toast(ime, R.string.cipher_replace_failed)
            return false
        }
        return true
    }

    private enum class Inserimento { RIUSCITO, TRONCATO, FALLITO }

    /** Field text and selection, to put it back as it was. */
    private class StatoCampo(val testo: String, val inizio: Int, val fine: Int)

    private fun statoDelCampo(ic: InputConnection): StatoCampo? {
        val extracted = runCatching { ic.getExtractedText(extractRequest(), 0) }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.partialStartOffset < 0) {
            val a = minOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, whole.length)
            val b = maxOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(a, whole.length)
            return StatoCampo(whole.toString(), a, b)
        }
        val prima = runCatching { ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) }.getOrNull() ?: return null
        val sel = runCatching { ic.getSelectedText(0) }.getOrNull() ?: ""
        val dopo = runCatching { ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) }.getOrNull() ?: ""
        return StatoCampo(
            prima.toString() + sel + dopo,
            prima.length,
            prima.length + sel.length,
        )
    }

    /**
     * Puts [testo] into the app's field in place of the selection, and checks
     * that the field became **exactly** what it should.
     *
     * The expected result is computed from the previous state (the field is
     * not necessarily empty: an app draft, a leftover newline, an earlier send
     * that did not leave). Comparing with the delivered text alone made the
     * check fail **even when the text had arrived**, and retrying put a second
     * copy in the field. If the field does not match, it is put back **as it
     * was**: a failure never leaves a copy around. If the field cut the text,
     * its limit is learned too (see [rimediaAlTroncamento]).
     */
    private fun inserisciNelCampo(
        ime: InputMethodService,
        ic: InputConnection,
        testo: String,
    ): Inserimento {
        val prima = statoDelCampo(ic)
        ic.beginBatchEdit()
        ic.finishComposingText()
        val consegnato = ic.commitText(testo, 1)
        ic.endBatchEdit()
        // A field that cannot be read back: there is nothing to base a check on,
        // so the answer is trusted, as [fieldIs] does.
        val dopo = (if (prima != null) testoDelCampo(ic) else null)
            ?: return if (consegnato) Inserimento.RIUSCITO else Inserimento.FALLITO
        prima ?: return Inserimento.FALLITO
        val testa = prima.testo.substring(0, prima.inizio)
        val coda = prima.testo.substring(prima.fine)
        if (consegnato && dopo == testa + testo + coda) return Inserimento.RIUSCITO

        val inMezzo = if (
            dopo.length >= testa.length + coda.length &&
            dopo.startsWith(testa) && dopo.endsWith(coda)
        ) dopo.substring(testa.length, dopo.length - coda.length) else null
        val troncato = inMezzo != null && inMezzo.isNotEmpty() &&
            inMezzo.length < testo.length && testo.startsWith(inMezzo)

        // Remove ONLY what was inserted, selecting it by position. "Select all"
        // and rewrite is the obvious path and is wrong: in a field that ignores
        // that command the rewrite is APPENDED, and the restore would create
        // exactly the duplicate it must avoid. It remains a last resort only if
        // head and tail are not intact, and then the result is checked.
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (inMezzo != null) {
            ic.setSelection(testa.length, dopo.length - coda.length)
            ic.commitText("", 1)
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText(prima.testo, 1)
        }
        ic.setSelection(prima.inizio, prima.fine)
        ic.endBatchEdit()

        if (!troncato) return Inserimento.FALLITO
        imparaLimite(ime, dopo.length)
        return Inserimento.TRONCATO
    }

    /**
     * Remembers for this app the maximum length its field accepts. The caller
     * gives the notice: what happens next differs for a blob, which is split
     * into parts, and for plaintext, which is not.
     */
    private fun imparaLimite(ime: InputMethodService, caratteri: Int) {
        // Below this threshold not even the shortest split blob fits:
        // remembering it would be useless, and would let a field that pretends to
        // truncate make encryption impossible in that app.
        if (caratteri < LIMITE_MINIMO) return
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        if (pacchetto.isNotEmpty()) {
            ime.prefs().edit().putInt(PREF_LIMITE + pacchetto, caratteri).apply()
        }
    }

    private fun avvisaLimiteBlob(ime: InputMethodService) {
        KeyboardSwitcher.getInstance().showToast(
            ime.getString(R.string.cipher_field_limit_learned, limiteBlob(ime)),
            false,
        )
    }

    /**
     * The field truncated the blob: remove it and learn the limit.
     *
     * Some messaging apps stop at a fixed length (e.g. 2000 characters), well
     * under [MAX_BLOB_CHARS], without declaring it: `EditorInfo` carries no
     * maximum length. The truncated blob used to stay in the field, ready to be
     * sent and never to open, and retrying produced a blob of the same length.
     *
     * Now the field goes back to [ripristino] (empty from the row, the previous
     * plaintext in the field) and the length it accepted becomes the remembered
     * limit for that app: [encrypt] goes round again and splits into parts.
     *
     * @return `false` if it was not a truncation, and then the field was not
     *   touched.
     */
    private fun rimediaAlTroncamento(
        ime: InputMethodService,
        ic: InputConnection,
        blob: String,
        ripristino: String,
    ): Boolean {
        val rimasto = testoDelCampo(ic) ?: return false
        if (rimasto.isEmpty() || rimasto.length >= blob.length || !blob.startsWith(rimasto)) {
            return false
        }
        // The field contains exactly `rimasto`: select it by position rather
        // than "select all", which a field may ignore (and then the plaintext
        // would be appended after the truncated blob).
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.setSelection(0, rimasto.length)
        ic.commitText(ripristino, 1)
        ic.endBatchEdit()
        imparaLimite(ime, rimasto.length)
        avvisaLimiteBlob(ime)
        return true
    }

    /**
     * How long a blob can be in this field: [MAX_BLOB_CHARS], or less if this
     * app already truncated once. See [rimediaAlTroncamento].
     */
    private fun limiteBlob(ime: InputMethodService): Int {
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        if (pacchetto.isEmpty()) return MAX_BLOB_CHARS
        return ime.prefs().getInt(PREF_LIMITE + pacchetto, MAX_BLOB_CHARS)
            .coerceAtMost(MAX_BLOB_CHARS)
    }

    /**
     * Asks the app to send what was just put in the field. Returns `false` if
     * it could not: the caller decides what to say.
     *
     * ## Why it does not always work
     *
     * "Send" is a button of the **app**, and a keyboard cannot press it. The
     * only lever is `performEditorAction`, the action the field declares (the
     * one HeliBoard uses for the enter key). In chats that field is often
     * multi-line and **declares no action**, because sending with enter is a
     * user option (in Telegram: Settings → Chat → Send with Enter).
     *
     * `getImeOptionsActionIdFromEditorInfo` is reused rather than reading
     * `imeOptions` by hand: it is the same function that decides what the enter
     * key does, exceptions for known apps included.
     *
     * Without an action it falls back to a simulated enter key: see the
     * fallback below, and [togliACapo] for when the app takes it as a newline.
     *
     * @param consegnato what was just put in the field, to recognise it after
     *   the enter key.
     */
    private fun deliver(ime: InputMethodService, ic: InputConnection, consegnato: String): Boolean {
        if (!CipherSettings.isAutoSend(ime)) return false
        val info = ime.currentInputEditorInfo ?: return false
        val action = InputTypeUtils.getImeOptionsActionIdFromEditorInfo(info)
        val perAzione = when {
            action == InputTypeUtils.IME_ACTION_CUSTOM_LABEL -> ic.performEditorAction(info.actionId)
            action != EditorInfo.IME_ACTION_NONE -> ic.performEditorAction(action)
            else -> false
        }
        if (perAzione) return true

        // Fallback: the real enter key.
        //
        // Needed for chats, the case that matters. A messaging field is
        // multi-line and **declares no action**: with "send with enter" on, the
        // app does not change what it declares, it intercepts the key. So
        // `performEditorAction` goes nowhere.
        //
        // In a multi-line field with send-with-enter off this inserts a newline
        // after the delivered blob. A trailing newline does not spoil it, because
        // recognition tolerates surrounding text (see the core's format tests).
        // The cost is an empty line at the end of the field.
        val premuto = ic.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        ) && ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        // `sendKeyEvent` only says the event was sent, not that the app sent the
        // message: there is no way to know, and promising it would be worse than
        // saying nothing.
        if (!premuto) {
            val pacchetto = info.packageName.orEmpty()
            avvisoUnaVolta(ime, "invio_$pacchetto", R.string.cipher_send_unavailable)
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                togliACapo(ime, consegnato)
            }, ATTESA_INVIO_MS)
        }
        return premuto
    }

    /**
     * The app took the enter key as a newline: remove it.
     *
     * Happens where send-with-enter is off. The field kept the delivered text
     * **plus** a newline: every SMS left with an empty line at the end, and the
     * comparison with [Consegnato] failed, so on reopening the row took back the
     * message ready to go, a blob the lock would then encrypt a second time.
     *
     * The field is touched only if it contains **exactly** that: if the user
     * already typed something else, or the app sent and the field is empty,
     * there is nothing to fix.
     */
    private fun togliACapo(ime: InputMethodService, consegnato: String) {
        val ic = appConnection(ime) ?: return
        if (testoDelCampo(ic) != consegnato + "\n") return
        // One character, behind the cursor, and only if it is the trailing
        // newline: no "select all" and rewrite, which in a field ignoring the
        // selection would add a second copy of the message.
        val primaDelCursore = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()
        val dopoIlCursore = runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull()
        if (primaDelCursore?.toString() != "\n" || !dopoIlCursore.isNullOrEmpty()) return
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(1, 0)
        ic.endBatchEdit()
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        avvisoUnaVolta(ime, "invio_$pacchetto", R.string.cipher_send_unavailable)
    }

    /**
     * The connection to the app, not to the compose buffer.
     *
     * With compose mode active `currentInputConnection` is redirected to the
     * keyboard's buffer: anything that must really reach the app (the encrypted
     * blob, the identity card) needs the real one. Reading the field to decrypt
     * goes through here for the same reason: the blob is in the app.
     */
    private fun appConnection(ime: InputMethodService): InputConnection? =
        (ime as? LatinIME)?.appInputConnection ?: ime.currentInputConnection

    /**
     * Writes our identity card into the field.
     *
     * The first-contact bootstrap. Every static-scheme message already carries
     * `sender_pub`, so *receiving* a message pins the sender automatically;
     * only the very first direction is left, before the other side has written
     * anything.
     *
     * Through the field and not the clipboard, because of the asymmetry that
     * governs the whole project: **writing into the field is native for an IME,
     * reading is not.** The keyboard sees the input field, never the chat
     * history.
     *
     * From outside the card is indistinguishable from a message: same sentinel,
     * the kind is a byte INSIDE the body, and the length is randomised so a
     * regex cannot isolate it.
     *
     * *Discoverability note:* a long press is hidden. The visible entry point is
     * the contacts UI (Settings -> Contacts); this is the quick gesture.
     */
    fun insertIdentityCard(ime: InputMethodService) {
        if (!ready(ime)) return
        // Into the app's field: the card is meant to be sent.
        val ic = appConnection(ime) ?: return
        val card = CipherCore.nativeIdentityCard()
        if (card == null) {
            toast(ime, R.string.cipher_unavailable)
            return
        }
        // Inserted at the cursor, not replacing: if the field already has text
        // there is no reason to discard it. Recognition tolerates context, so a
        // card in the middle of other text stays readable.
        ic.commitText(card, 1)
        avvisoUnaVolta(ime, "card", R.string.cipher_card_inserted)
    }

    /**
     * Moves into the row the text that was already in the app's field.
     *
     * For the most mundane and annoying case: you open a chat, the app restores
     * a draft (or you had typed before turning the mode on), and that text stays
     * on screen out of the keyboard's reach. Two visible boxes and only one
     * answering the keys is worse than no row.
     *
     * **Adopted only if the field really emptied.** If deletion fails, the text
     * would be in both places and, when encrypting, end up in the blob *and*
     * next to it in clear.
     *
     * **And only if the row can receive it.** Emptying the field is half of a
     * move: without the other half it is a deletion.
     */
    @JvmOverloads
    fun adoptFieldText(ime: InputMethodService, primaryCode: Int = 0) {
        // NEVER on the encryption keys. They are the only ones reading the app's
        // field themselves ("decrypt" looks for the blob there, "encrypt" falls
        // back to it when the row is empty), and adopting an instant earlier
        // would pull it from under them.
        // The lower bound is the LAST encryption code, not the first one added:
        // adding a new one without moving it would leave that key outside the
        // range.
        if (primaryCode <= KeyCode.CIPHER_ENCRYPT && primaryCode >= KeyCode.CIPHER_GALLERY) {
            return
        }
        // NEVER on enter. It is the key that SENDS what is in the field, so
        // adopting there would take it away an instant before it leaves: the
        // message disappears from the field, reappears in the row, and nothing
        // was sent (e.g. right after "send in clear").
        //
        // Everything else still adopts, deletion included: fixing a typo in what
        // was just delivered is why adoption exists.
        if (primaryCode == Constants.CODE_ENTER ||
            primaryCode == KeyCode.SHIFT_ENTER ||
            primaryCode == KeyCode.ACTION_NEXT ||
            primaryCode == KeyCode.ACTION_PREVIOUS
        ) {
            // A manual send is when a part leaves: check whether another one
            // should take its place. The real check happens in
            // [consegnaProssimaParte] half a second later, on the empty field:
            // pressing enter does not mean the message was sent.
            if (primaryCode == Constants.CODE_ENTER) dopoInvio(ime)
            return
        }
        if (!CipherSettings.isEnabled(ime)) return
        if (!CipherCompose.isEnabled() || !CipherCompose.isEmptyBuffer()) return
        // NEVER with the row suppressed. On a password field the row does not
        // appear and receives nothing (see CipherCompose.onInputStarted), so
        // `adopt` refuses the text: there is nowhere to move it, and removing it
        // from the field would only destroy it.
        //
        // This was the PIN pad: each digit was taken from the field and handed
        // to nobody, so only the last digit remained and the PIN could not be
        // typed. `adopt` refusing is not enough, because deletion from the field
        // happens BEFORE (it must, so plaintext is never in two places at once);
        // this is the only point where it can be avoided.
        if (CipherCompose.isSuppressed()) return
        val ic = appConnection(ime) ?: return
        val field = readField(ime, ic) ?: return
        if (field.text.isEmpty()) return
        // On reopening (no key: `primaryCode` zero) do not take back what the
        // row itself just delivered. See [Consegnato]. With a key, yes: fixing
        // what was just delivered is why adoption exists.
        if (primaryCode == 0 && Consegnato.e(field.text)) return
        if (!replaceField(ic, field, "")) return
        CipherCompose.adopt(field.text)
    }

    /**
     * Turns the compose row on and off from the toolbar.
     *
     * The same as the switch in settings, but under the thumb: where you type
     * is not a one-off configuration, it changes from conversation to
     * conversation (inside the row with people who have the keyboard, in the
     * field with everyone else).
     *
     * **No keyboard rebuild.** The toolbar always contains the encryption keys
     * and `CipherCompose.reload` shows or hides them in place, together with the
     * row. The new height is picked up by `onComputeInsets` on the next layout.
     */
    fun toggleCompose(ime: InputMethodService) {
        // The key moves keystrokes between the row and the app's field without
        // restarting input, so HeliBoard resets nothing by itself: the word
        // being composed in the row used to be written into the app's field at
        // the next keystroke, together with suggestions computed on the
        // plaintext. Turning the row off sent the app exactly what the row
        // protects.
        val prima = CipherCompose.connection()
        try {
            accendiOSpegniRiga(ime)
        } finally {
            if (CipherCompose.connection() !== prima) {
                (ime as? LatinIME)?.onCipherTargetChanged(prima == null)
            }
        }
    }

    private fun accendiOSpegniRiga(ime: InputMethodService) {
        val prefs = ime.prefs()

        // If the row is there, the key turns it off, and turns encryption off.
        if (CipherCompose.rigaASchermo()) {
            prefs.edit().putBoolean(CipherSettings.PREF_ENABLED, false).apply()
            CipherCompose.reload(ime)
            avvisoUnaVolta(ime, "riga_spenta", R.string.cipher_compose_off)
            return
        }

        // If the row is NOT there, the key wants it here. Encryption is turned
        // on first if it was off: this key is the way to turn it back on.
        if (!CipherSettings.isEnabled(prefs)) {
            prefs.edit().putBoolean(CipherSettings.PREF_ENABLED, true).apply()
            CipherCompose.reload(ime)
            // Realign with the current field BEFORE repainting: while off, the
            // row kept owner and suppression from the last app where it was on,
            // and the label would show that app's recipient.
            CipherCompose.risincronizza(ime)
        }
        if (CipherCompose.rigaASchermo()) {
            avvisoUnaVolta(ime, "riga_accesa", R.string.cipher_compose_on)
            return
        }

        // The row is still not there: this field is not classified as a message
        // field. The classification is our guess, not a prohibition: if the user
        // presses the key here, their request wins. Encryption on without a row
        // to type in is not a mode, it is a fault.
        if (!CipherCompose.forza(ime)) {
            // The one case that is not overridden: it would show on screen what
            // the field hides behind dots.
            toast(ime, R.string.cipher_compose_password)
            return
        }
        toast(ime, R.string.cipher_compose_forced)
    }

    /**
     * The last blob that came through here, so it is not processed twice.
     *
     * A digest rather than the text: keeping it in a static field would keep it
     * in memory beyond the moment it was needed.
     */
    private object Ripetuto {
        private const val FINESTRA_MS = 10_000L

        @Volatile
        private var ultimo: String? = null

        @Volatile
        private var quando = 0L

        fun gia(testo: String): Boolean {
            val impronta = improntaDi(testo) ?: return false
            val adesso = android.os.SystemClock.elapsedRealtime()
            val ripetuto = impronta == ultimo && adesso - quando < FINESTRA_MS
            ultimo = impronta
            quando = adesso
            return ripetuto
        }
    }

    private fun improntaDi(testo: String): String? = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(testo.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * What the row just delivered to the app's field (blob or plaintext), so it
     * is not taken back.
     *
     * [adoptFieldText] moves into the row the text found in the field when the
     * keyboard reopens. Text just delivered is also "text in the field", and an
     * app that restarts input right after (search bars do it constantly) saw it
     * snatched away: "send in clear" put the search term in the field, the row
     * took it back an instant later, and the search bar stayed empty.
     *
     * A digest, not the text, as for [Ripetuto].
     *
     * Trailing whitespace does not count: the simulated enter of [deliver] can
     * leave a newline after the delivered text. [togliACapo] usually removes it,
     * but the user may switch app first.
     */
    private object Consegnato {
        @Volatile
        private var impronta: String? = null

        fun segna(testo: String) {
            impronta = improntaDi(testo.trimEnd())
        }

        fun e(testo: CharSequence): Boolean {
            val attesa = impronta ?: return false
            return attesa == improntaDi(testo.toString().trimEnd())
        }
    }

    /**
     * What happened to the copied blob.
     *
     * Three outcomes rather than a boolean because there really are three:
     * shown in the panel (the window must be requested), already handled on its
     * own (the caller must do NOTHING, or it would reprocess the blob), or not
     * ours to handle here.
     */

    private enum class Esito { PANNELLO, GESTITO, NO }

    /**
     * Decrypts and shows inside the keyboard. Returns [Esito.NO] when it could
     * not, and then the caller goes on with the activity.
     *
     * Anything other than a successful text message is left to
     * [DecryptActivity], which already handles every outcome: two
     * implementations of the same outcomes diverge at the first new one.
     */
    private fun mostraNelPannello(ime: InputMethodService, blob: String): Esito {
        if (CipherIdentity.ensureReady(ime) != CipherState.Ready) return Esito.NO
        val result = CipherCore.IncomingResult()
        val code = CipherCore.nativeHandleIncomingText(
            ime.currentInputEditorInfo?.packageName.orEmpty(),
            blob,
            System.currentTimeMillis() / 1000,
            result,
        )
        if (code != CipherCore.OK) return Esito.NO
        CipherCompose.segnaCifrato()
        // Encryption is never switched back on automatically here: a setting the
        // program changes by itself is not a setting. Someone opening a blob is
        // usually replying, but the right answer is to tell them, not to decide
        // for them.
        //
        // ## A never-seen identity card cannot be reprocessed
        //
        // `nativeHandleIncomingText` is NOT side-effect free: it pins an unseen
        // key (TOFU) while reading it. Handing it to the activity would call the
        // core again on the same blob, and the second time it would be **already
        // known**: no naming dialog, and the contact saved without a name.
        //
        // So the same screen `DecryptActivity.showIdentityCard` would open is
        // opened from here, with the same input. The peer key is PUBLIC: no
        // secret travels in the intent. If it was already known, reprocessing
        // gives the same outcome and the activity handles it as usual.
        if (result.kind == CipherCore.KIND_IDENTITY_CARD) {
            val peer = result.senderKey
            if (result.alreadyPinned != 0 || peer == null) return Esito.NO
            // The pin just made goes to disk: it lives only in memory until
            // written, and after a restart TOFU would start over.
            if (!CipherIdentity.persistKeyring(ime)) {
                toast(ime, R.string.cipher_keyring_not_saved)
            }
            scalaDiApertura(ime, blob, ContactsActivity.intentNomina(ime, peer))
            return Esito.GESTITO
        }
        // One of our own messages reopened too: it is plaintext like the other,
        // and treating it differently would be inexplicable.
        val mio = result.kind == CipherCore.KIND_OWN_MESSAGE
        if (result.kind != CipherCore.KIND_MESSAGE && !mio) return Esito.NO
        // The current recipient on disk, as `DecryptActivity` already does.
        //
        // The core chooses it in memory as soon as it decrypts, but that choice
        // dies with the process. `CipherRecipients` exists for this; without it,
        // after a keyboard restart the recipient was gone.
        val letturaDa = result.senderKey
        if (letturaDa != null) {
            val app = ime.currentInputEditorInfo?.packageName.orEmpty()
            // As in `DecryptActivity`: with a known app the recipient goes there;
            // without one, it stays pending for the first chat opened.
            val gruppoLetto = result.isGroup == 1
            if (app.isEmpty()) {
                // Without an app nothing can be pinned for anyone; for people the
                // pending delivery remains, for groups not (choosing a group is
                // per app like everything else).
                if (!gruppoLetto) CipherRecipients.ricordaUltimoLetto(ime, letturaDa)
            } else if (gruppoLetto) {
                // ## A group read selects THE GROUP, not the writer
                //
                // `remember` pins a person and **clears the group**: after reading
                // in the group, the reply would have gone privately to the sender.
                //
                // The group is recognised only by comparing with saved groups: the
                // blob does not carry the member list, by choice (decision K). If
                // recognition is not unique, nothing is chosen or guessed: the user
                // is told and the recipient stays where it was.
                val gruppo = CipherGroups.riconosci(ime, letturaDa, result.recipientCount)
                if (gruppo != null) {
                    CipherGroups.scegli(ime, app, gruppo.nome)
                } else {
                    toast(ime, R.string.cipher_group_unknown)
                }
                CipherCompose.updateRecipient(app)
            } else {
                CipherRecipients.remember(ime, app, letturaDa)
                // Repaint **now**, do not wait for the next `onStartInput`.
                //
                // Decrypting from the keyboard, focus never leaves the app, so
                // `onInputStarted` does not run and nobody repaints the label: the
                // row kept showing the previous name until a field change, exactly
                // when the change should be visible.
                CipherCompose.updateRecipient(app)
            }
        }
        val bytes = result.plaintext ?: return Esito.NO
        // The core returns a ByteArray, not a String, so it can be zeroized.
        // Showing it needs a CharSequence, so a non-zeroizable copy exists
        // anyway: the guarantee stops here, mitigated by FLAG_SECURE on the
        // window and by zeroizing the array.
        val chiaro = try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
        // The pin just made goes to disk, exactly as in the activity.
        if (!CipherIdentity.persistKeyring(ime)) {
            toast(ime, R.string.cipher_keyring_not_saved)
        }
        val gruppo = result.isGroup == 1
        val nome = result.senderLabel ?: result.senderFingerprint.orEmpty()
        // On one of our own messages "compared in person" is not shown: there is
        // no identity to verify. Same rule as DecryptActivity.showMessage.
        val intestazione = when {
            // In a group NO author is shown (decision K6): the text is encrypted
            // with a key every member has, so any member could have rewritten it
            // keeping the original slots. A name next to rewritable text is worse
            // than none: an invented guarantee.
            gruppo -> ime.getString(R.string.cipher_group_header)
            mio -> ime.getString(R.string.cipher_own_message_to, nome)
            result.verified == 1 -> ime.getString(R.string.cipher_sender_verified, nome)
            else -> nome
        }
        val quando = runCatching {
            android.text.format.DateFormat.getDateFormat(ime).format(java.util.Date(result.sentAtUnix * 1000)) +
                " " +
                android.text.format.DateFormat.getTimeFormat(ime).format(java.util.Date(result.sentAtUnix * 1000))
        }.getOrDefault("")
        // A group message says it is one, and states the price (decision K1): a
        // group has no forward secrecy, and keeping quiet would make the reader
        // believe they have a guarantee they only have one-to-one.
        val riga = if (result.isGroup == 1) {
            quando + "  ·  " + ime.getString(R.string.cipher_group_message, result.recipientCount)
        } else {
            quando
        }
        CipherPanel.mostra(intestazione, riga, chiaro)
        return Esito.PANNELLO
    }

    /**
     * How long the blob for this text will be, before producing it.
     *
     * Estimated rather than encrypted and measured: discovering after encrypting
     * that it cannot be sent would mean having touched the field already. The
     * estimate is exact to within one character, because z-base-32 is
     * deterministic: eight characters per five bytes.
     *
     * @param slot how many slots the blob will have, sender included. `0` for a
     *   one-to-one message.
     */
    private fun stimaBlob(testo: String, slot: Int = 0): Int {
        val involucro = if (slot > 0) {
            OVERHEAD_GRUPPO_BYTES + slot * SLOT_BYTES
        } else {
            OVERHEAD_BYTES
        }
        val byte = testo.toByteArray().size + involucro
        return SENTINEL_LEN + (byte * 8 + 4) / 5
    }

    /**
     * The message just copied is one of ours: open it.
     *
     * Called by `ClipboardHistoryManager` when a new item arrives, the only
     * moment a keyboard can notice something without going to look for it. The
     * text there has already been read for the history: no second clipboard
     * access, no system toast.
     *
     * Recognition goes through the core (`nativeLooksLikeOurBlob`): it only
     * checks the sentinel and minimum length, does not decrypt and does not
     * touch the keyring. Writing that check here would be a second source of
     * truth on the format.
     */
    fun autoDecrypt(ime: InputMethodService, testo: CharSequence) {
        if (!CipherSettings.isAutoOpen(ime)) return
        val contenuto = testo.toString()
        if (!CipherCore.available || !CipherCore.nativeLooksLikeOurBlob(contenuto)) return
        // ## Delivery is at-least-once, and this call has side effects
        //
        // `nativeHandleIncomingText` is NOT a read: it pins an unseen sender,
        // advances the chain, burns a conversation. Doing it twice on the same
        // blob makes the contact "already known" the second time, with two
        // screens stacked.
        //
        // The clipboard change can arrive more than once for a single copy. The
        // only correct defence against repeated delivery is making the
        // non-idempotent step idempotent at the entrance.
        //
        // A marker SEPARATE from `DecryptActivity`'s: a shared one would make the
        // activity discard exactly the opening this function just requested.
        if (Ripetuto.gia(contenuto)) return
        // ## First: the panel inside the keyboard
        //
        // Not an activity launch, so no restriction applies: the keyboard window
        // is ours already. It covers the common case (a text message that
        // decrypts) and leaves everything else to the activity, so the six
        // outcomes have a single implementation.
        //
        // **The window must be requested.** Filling the panel does not show it:
        // if the keyboard is not on screen it stays invisible. `requestShowSelf`
        // is not an activity launch: it is the keyboard asking for its own
        // window. It needs an active input session, so it may not be enough: the
        // outcome is checked and, if needed, the launch ladder below is used.
        val esito = if (CipherPanel.disponibile()) mostraNelPannello(ime, contenuto) else Esito.NO
        if (esito == Esito.GESTITO) return
        if (esito == Esito.PANNELLO) {
            if (ime.isInputViewShown) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { ime.requestShowSelf(0) }
            }
            // No intent here: with the message in the panel it is not needed,
            // and building one would issue a [CipherHandoff] token, which holds
            // only one and would overwrite that of a keyboard screen just opened.
            attendiFinestra(ime, contenuto, null, CipherPanel.contenuto())
            return
        }
        scalaDiApertura(ime, contenuto)
    }

    /** An opening waiting for the keyboard window. See [attendiFinestra]. */
    private class Attesa(
        val contenuto: String,
        /** `null` when the message is in the panel: not needed there. */
        val intent: Intent?,
        /**
         * What the panel showed, if the message went there. If the panel was
         * cleared before the window arrived, it is shown again from here:
         * decrypting again with forward secrecy would not work, the key is
         * already used.
         */
        val pannello: Triple<String, String, String>?,
        /** `0` while waiting the first time; then the [ATTESA_TARDIVA_MS] limit. */
        val scadenza: Long = 0L,
    )

    @Volatile
    private var attesa: Attesa? = null

    /**
     * Waits for the keyboard window to appear, and serves it **when it appears**,
     * not when a timer expires.
     *
     * Checking `isInputViewShown` after a fixed delay failed on a busy phone:
     * the window arrived later, the panel was closed, the window was requested
     * again, and the queued window requests then showed an empty keyboard. The
     * copied message did not open in any way, and nothing said so.
     *
     * Now the event drives it: [finestraComparsa], called from
     * `LatinIME.onWindowShown`. The timer remains as a limit: when it expires
     * the panel is cleared (the view must not hold invisible plaintext), the
     * notification is offered, and the wait stays alive for
     * [ATTESA_TARDIVA_MS] so a late window shows the message instead of
     * appearing empty.
     */
    private fun attendiFinestra(
        ime: InputMethodService,
        contenuto: String,
        intent: Intent?,
        pannello: Triple<String, String, String>?,
    ) {
        val questa = Attesa(contenuto, intent, pannello)
        attesa = questa
        Handler(Looper.getMainLooper()).postDelayed({
            if (attesa !== questa) return@postDelayed
            if (ime.isInputViewShown) {
                finestraComparsa(ime)
                return@postDelayed
            }
            if (pannello != null) CipherPanel.chiudi()
            CipherNotification.offer(ime, contenuto)
            attesa = Attesa(
                contenuto,
                intent,
                pannello,
                scadenza = android.os.SystemClock.elapsedRealtime() + ATTESA_TARDIVA_MS,
            )
        }, ATTESA_FINESTRA_MS)
    }

    /**
     * The keyboard window appeared: if a copied message was waiting for it, show
     * it now.
     *
     * If it was in the panel and the panel is still open, nothing to do. If the
     * panel was cleared meanwhile (by the timer, or by the new view hierarchy
     * the keyboard attaches when appearing), it is refilled with what it showed,
     * without decrypting again. If the message was for [DecryptActivity], that
     * is opened now, with the window present.
     */
    fun finestraComparsa(ime: InputMethodService) {
        val a = attesa ?: return
        attesa = null
        if (a.scadenza != 0L && android.os.SystemClock.elapsedRealtime() > a.scadenza) return
        // Shown here, the notification is no longer needed: tapping it would
        // open the same message a second time.
        CipherNotification.dismiss(ime)
        val pannello = a.pannello
        if (pannello == null) {
            a.intent?.let { intent -> runCatching { ime.startActivity(intent) } }
            return
        }
        if (CipherPanel.isAperto()) return
        // Cleared before the window arrived: by the timer, or by the new view
        // hierarchy the keyboard attaches when appearing.
        CipherPanel.mostra(pannello.first, pannello.second, pannello.third)
    }

    /** The intent that brings [contenuto] to [DecryptActivity] from the keyboard. */
    private fun intentDiLettura(ime: InputMethodService, contenuto: String): Intent =
        Intent(ime, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, contenuto)
            putExtra(
                CipherHandoff.extraName(),
                CipherHandoff.issue(ime.currentInputEditorInfo?.packageName.orEmpty()),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Opens [daAprire] (one of the ways to bring up [DecryptActivity] when the
     * panel inside the keyboard is not usable), once.
     *
     * ## Why there is no retry
     *
     * A refused launch signals nothing: `startActivity` returns as if it
     * worked. Retrying after a timeout always fired (the activity measurably
     * takes over a second to appear), and since `nativeHandleIncomingText` is
     * not a read, the second opening reprocessed the blob: identity card pinned
     * twice, the second time "already known".
     *
     * Instead of guessing the outcome, the condition for the launch to be
     * allowed is created first: having a window on screen, which is
     * **observable** (`isInputViewShown`) while the launch outcome is not.
     * `requestShowSelf` is not an activity launch and is not subject to those
     * restrictions.
     *
     * If the window does not arrive (no active input session), nothing can be
     * opened, and then the fallback is a fact, not a guess.
     */
    private fun scalaDiApertura(
        ime: InputMethodService,
        contenuto: String,
        daAprire: Intent? = null,
    ) {
        val intent = daAprire ?: intentDiLettura(ime, contenuto)
        if (ime.isInputViewShown) {
            runCatching { ime.startActivity(intent) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { ime.requestShowSelf(0) }
        }
        attendiFinestra(ime, contenuto, intent, null)
    }

    /** Like [chiediDestinatario], but callable from outside: from the name in the row. */
    fun chiediDestinatarioOra(ime: InputMethodService) = chiediDestinatario(ime)

    /**
     * Like [allegato], with the picker already filtered on images and video.
     * Sending a photo is the second most frequent gesture here, and this saves
     * two taps.
     */
    fun galleria(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intentAllegato(ime, soloMedia = true)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    /**
     * Sends an encrypted file from the keyboard.
     *
     * The key **does not choose the recipient**: it opens the contact list and
     * the user picks the person (decision G4). The recipient remembered for the
     * app is not used even when present: a file sent to the wrong person cannot
     * be withdrawn and, unlike a message, stays on the recipient's phone.
     *
     * An activity rather than a keyboard panel: it needs the system document
     * picker, which an IME cannot open by itself, and `FLAG_SECURE`.
     */
    fun allegato(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intentAllegato(ime, soloMedia = false)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }


    /** Opens the list of encrypted contacts. */
    fun contatti(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intent(ime)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    /**
     * Opens the contact list with the full-destruction confirmation already on
     * screen: identity, keyring, names, groups, tombstones, statistics, share
     * folder and clipboard.
     *
     * **It destroys nothing by itself.** The gesture is irreversible and the
     * dialog lists what will be lost, like every other destruction in this app.
     * What changes is only the number of steps: from "unlock, open keyboard
     * settings, contacts, scroll, confirm" to "long-press a key, confirm".
     *
     * The limit, stated rather than worked around: it is a **visible** gesture.
     * A hidden one would mean an app PIN with a duress PIN, and there is no app
     * PIN here (it would have to be asked on every use of the keyboard). This
     * defends "I am about to hand over the phone", not "it is being snatched".
     */
    fun distruggiTutto(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intentDistruggi(ime)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    /**
     * The microphone is forbidden while the encrypted row is on screen.
     *
     * Returns `true` if the caller must **not** open voice input.
     *
     * See the comment in `LatinIME.onEvent`: voice input is another keyboard
     * that talks directly to the app, and dictation would end up in clear in the
     * field, with the encrypted row in sight suggesting otherwise. It cannot be
     * redirected: it does not go through us.
     *
     * The reason is shown rather than doing nothing: a key that does not respond
     * looks broken.
     */
    fun microfonoVietato(ime: InputMethodService): Boolean {
        if (!CipherCompose.rigaASchermo()) return false
        toast(ime, R.string.cipher_voice_blocked)
        return true
    }

    /**
     * Opens recipient selection for the app being typed in.
     *
     * The package travels in a [CipherHandoff] token, not as an ordinary extra:
     * extras can be written by anyone, and attributing the choice to the wrong
     * app would redirect whom we encrypt for.
     */
    private fun chiediDestinatario(ime: InputMethodService) {
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        if (pacchetto.isEmpty()) {
            toast(ime, R.string.cipher_no_recipient)
            return
        }
        runCatching { ime.startActivity(RecipientActivity.intent(ime, pacchetto)) }
            .onFailure { toast(ime, R.string.cipher_no_recipient) }
    }

    /**
     * Delivers the composed text to the app **in clear**, without encrypting.
     *
     * Only in compose mode, for the trivial and very frequent case that would
     * otherwise force turning the mode off: sending "on my way", an address or
     * a link to someone who does not have this keyboard.
     *
     * Delivers the text to the field and then, **if auto-send is on**, asks the
     * app to send it, exactly as the lock key does. To review before sending,
     * turn off *Send immediately* in Settings → Encryption.
     */
    fun sendPlain(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        if (!CipherCompose.isEnabled()) return
        if (CipherCompose.inSospeso()) {
            toast(ime, R.string.cipher_pending_choose)
            return
        }
        val ic = appConnection(ime) ?: return
        val text = CipherCompose.text()
        if (text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_send)
            return
        }
        // After encrypting or reading, plaintext may be a wrong key: ask, once.
        // See CipherCompose.chiediPrimaDelChiaro.
        if (CipherCompose.chiediPrimaDelChiaro()) return
        // Clear the row only after a SUCCESSFUL delivery: clearing first, or
        // regardless, loses the text when delivery fails. Even more important
        // here than for the blob: this is the user's own text.
        when (inserisciNelCampo(ime, ic, text)) {
            Inserimento.RIUSCITO -> Unit
            // Plaintext is not split into parts: say how much fits.
            Inserimento.TRONCATO -> {
                KeyboardSwitcher.getInstance().showToast(
                    ime.getString(R.string.cipher_plain_too_long, limiteBlob(ime)),
                    false,
                )
                return
            }
            Inserimento.FALLITO -> {
                toast(ime, R.string.cipher_send_failed_kept)
                return
            }
        }
            // See [consegna]: remember the whole field.
        val campo = testoDelCampo(ic) ?: text
        Consegnato.segna(campo)
        CipherCompose.clear()
        if (!deliver(ime, ic, campo)) avvisoUnaVolta(ime, "chiaro", R.string.cipher_sent_plain)
    }

    /**
     * Sends the field content to [DecryptActivity].
     *
     * The plaintext does NOT go back into the field, and that is the central
     * point: the field belongs to the chat app, so writing decrypted text there
     * would hand it to exactly the application this project exists to keep it
     * from. Plaintext is shown only in our own window, which is `FLAG_SECURE`.
     */
    fun decrypt(ime: InputMethodService) = decrypt(ime, clipboardOnly = false)

    /**
     * Long press on "decrypt": skips the field and goes straight to the
     * clipboard.
     *
     * For when the field is NOT empty but holds something else (a reply already
     * started). Without it the short press would use that text and say "not
     * encrypted", which is true and useless.
     */
    fun decryptFromClipboard(ime: InputMethodService) = decrypt(ime, clipboardOnly = true)

    private fun decrypt(ime: InputMethodService, clipboardOnly: Boolean) {
        // `prontoPerLeggere` rather than `ready`: decryption works even with the
        // main switch off. See the comment on that function.
        if (!prontoPerLeggere(ime)) return
        val text = source(ime, clipboardOnly) ?: return

        // ## The panel inside the keyboard first, as for automatic opening
        //
        // The panel covers the common case (a text message) and leaves
        // everything else to the activity: attachments, identity cards, burns,
        // errors. So the six outcomes are implemented once.
        if (CipherPanel.disponibile() && mostraNelPannello(ime, text.toString()) != Esito.NO) {
            return
        }

        // ACTION_SEND rather than our own action: DecryptActivity already handles
        // it for the share sheet, and a single path inside the activity means a
        // single implementation of the six outcomes.
        val intent = Intent(ime, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            // The package of the app being typed in, which only the IME knows.
            // Without it, DecryptActivity would derive it from `referrer` and get
            // the KEYBOARD's package (on this path the caller is us): the
            // recipient would be recorded under the keyboard and "decrypting sets
            // the recipient" would never apply to the right app.
            // A token, not the package: extras can be written by anyone, a random
            // value generated in this process cannot.
            putExtra(
                CipherHandoff.extraName(),
                CipherHandoff.issue(ime.currentInputEditorInfo?.packageName.orEmpty()),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
    }

    /**
     * Where to take the text to decrypt from: the field first, then the
     * clipboard.
     *
     * The field is free and has no side effects, while reading the clipboard on
     * Android 12+ shows a system toast and pulls in whatever the user copied. The
     * clipboard is used only when the field has nothing to offer.
     *
     * `null` means "the user was already told what is wrong": the caller just
     * returns.
     */
    private fun source(ime: InputMethodService, clipboardOnly: Boolean): CharSequence? {
        if (!clipboardOnly) {
            // ## The compose row comes BEFORE the app's field
            //
            // With the row active, a pasted blob lands there, not in the field.
            // Looking at the field first (empty by construction in that mode)
            // skipped the one place the text could be.
            //
            // The row is not cleared afterwards: that text belongs to the user.
            val composto = CipherCompose.text()
            if (composto.isNotEmpty()) return composto
            val ic = appConnection(ime)
            if (ic != null) {
                // `null` here means "field too long, already reported", not "field
                // empty". Falling back to the clipboard would decrypt something
                // other than what the user was looking at.
                val field = readField(ime, ic) ?: return null
                if (field.text.isNotEmpty()) return field.text
            }
        }
        // The clipboard description before the content: the only call that does
        // not trigger the system "pasted from clipboard" toast. See
        // CipherClipboard.
        //
        // But it is an OPTIMISATION and must not be able to kill the feature:
        // the description can say no for reasons unrelated to the text being
        // there (a MIME type other than text/plain, a ROM restriction). So
        // reading is attempted anyway. The price is the system toast when there
        // really is nothing, after the user pressed "decrypt" on purpose.
        val clip = CipherClipboard.read(ime)
        if (clip.isNullOrEmpty()) {
            toast(
                ime,
                if (CipherClipboard.hasText(ime)) R.string.cipher_clipboard_unreadable
                else R.string.cipher_nothing_to_decrypt,
            )
            return null
        }
        return clip
    }

    /**
     * Initialises the core and turns a result other than `Ready` into a message.
     * Three cases, three messages: saying "unavailable" to someone whose phone is
     * just locked would send them looking for a fault that does not exist.
     */
    private fun ready(ime: InputMethodService): Boolean =
        // The main switch first: with encryption off the keys are not there, but
        // a code can still arrive from a custom shortcut left in a saved profile.
        if (!CipherSettings.isEnabled(ime)) false
        else prontoPerLeggere(ime)

    /**
     * Like [ready], **without** the main switch.
     *
     * Reading and writing are different faculties: a blob stays readable as
     * long as one has the identity it was encrypted for, and turning the switch
     * off means "I don't want the compose row", not "discard the messages I
     * receive".
     *
     * An identity must still be ready: without one there is nothing to decrypt
     * with.
     */
    private fun prontoPerLeggere(ime: InputMethodService): Boolean =
        when (CipherIdentity.ensureReady(ime)) {
            CipherState.Ready -> true
            CipherState.Locked -> {
                toast(ime, R.string.cipher_locked)
                false
            }
            // TODO: Unreadable deserves a screen, not a toast: the only way out is
            //   resetIdentity, which destroys the identity and must be explained.
            else -> {
                toast(ime, R.string.cipher_unavailable)
                false
            }
        }

    /**
     * The field as read, with how much is before and after the cursor.
     *
     * The two counts are needed for replacement: without them one has to guess
     * how much to delete on each side, and guessing too little leaves plaintext
     * in the field next to the blob.
     */
    private class Field(val text: String, val before: Int, val after: Int)

    /**
     * The whole field, not just what is before the cursor: the message is
     * encrypted, not the part written so far.
     *
     * Two ways, and the order matters.
     *
     * `getExtractedText` asks for the **complete** content in one call, and is
     * the only one that does not depend on how the app treats the composing
     * region. The other way's three calls (before, selection, after) must be
     * stitched together, and an app returning a partial one produces a mutilated
     * message that neither side notices.
     *
     * `partialStartOffset >= 0` means the app answered with a portion: that
     * result is unusable and the second way is used.
     */
    private fun readField(ime: InputMethodService, ic: InputConnection): Field? {
        // May throw if the connection dies between check and call: in a keyboard
        // an unhandled exception means a device you can no longer type on.
        val extracted = runCatching { ic.getExtractedText(extractRequest(), 0) }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.partialStartOffset < 0) {
            if (whole.length >= MAX_FIELD_CHARS) {
                toast(ime, R.string.cipher_text_too_long)
                return null
            }
            // selectionStart/End come from the app: they can be -1, or reversed
            // for a right-to-left selection. Normalised before use as lengths.
            val start = minOf(extracted.selectionStart, extracted.selectionEnd)
                .coerceIn(0, whole.length)
            val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
                .coerceIn(start, whole.length)
            return Field(whole.toString(), start, whole.length - end)
        }

        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) ?: ""
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) ?: ""
        // `getSelectedText` is essential: the two calls above return what is
        // BEFORE the start and AFTER the end of the selection, not the selection
        // itself. Without it, encrypting with text selected produced a blob
        // missing the selected part, which the replacement then deleted.
        val selected = ic.getSelectedText(0) ?: ""
        if (before.length >= MAX_FIELD_CHARS || after.length >= MAX_FIELD_CHARS) {
            toast(ime, R.string.cipher_text_too_long)
            return null
        }
        return Field(
            before.toString() + selected.toString() + after.toString(),
            before.length,
            after.length,
        )
    }

    /**
     * Deletes what was there and puts the blob. Returns `false` if afterwards
     * the field does not contain only the blob.
     *
     * In one batch: otherwise the app sees the field pass through the empty
     * state, and apps reacting to every change ("typing" indicator, saved
     * drafts) would record an intermediate state that never existed.
     *
     * `finishComposingText` rather than `commitText("")`: the former ends the
     * composing word **leaving it in the field**, where `before` already
     * counted it; the latter deletes it, and the counts would no longer
     * describe the field.
     *
     * ## Why there is a check
     *
     * Deleting is a request to the app, not something we do:
     * `deleteSurroundingText` can be ignored or partly applied by any editor
     * with its own `InputConnection`. That is the worst way this can fail:
     * plaintext stays in the field next to the blob, and whoever presses enter
     * sends the message in clear.
     *
     * So the field is read back, and if it is not just the blob it is retried
     * by selecting everything: `commitText` replaces the selection, a different
     * path that fails in different cases. If that is not enough either, the
     * caller tells the user.
     */
    private fun replaceField(ic: InputConnection, field: Field, blob: String): Boolean {
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(field.before, field.after)
        ic.commitText(blob, 1)
        ic.endBatchEdit()

        if (fieldIs(ic, blob)) return true

        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(blob, 1)
        ic.endBatchEdit()

        return fieldIs(ic, blob)
    }

    /**
     * Whether the field contains exactly [expected].
     *
     * When it cannot be read back the answer is `true`: without a read there is
     * nothing to base a correction on, and a blind second attempt would double
     * the blob rather than fix it.
     */
    private fun fieldIs(ic: InputConnection, expected: String): Boolean =
        (testoDelCampo(ic) ?: return true) == expected

    /** All of the field's text, or `null` if it cannot be read back. */
    private fun testoDelCampo(ic: InputConnection): String? {
        val extracted = runCatching { ic.getExtractedText(extractRequest(), 0) }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.partialStartOffset < 0) {
            return whole.toString()
        }
        val before = runCatching { ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) }.getOrNull()
            ?: return null
        val after = runCatching { ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) }.getOrNull() ?: ""
        return before.toString() + after.toString()
    }

    /**
     * `hintMaxChars`/`hintMaxLines` at zero: no suggested limit, we want the
     * whole field. They are hints, not guarantees, which is why readers still
     * check `partialStartOffset`.
     */
    private fun extractRequest() = ExtractedTextRequest().apply {
        token = 0
        hintMaxChars = 0
        hintMaxLines = 0
    }

    /**
     * Notices that **explain how something works**: shown the first time, then
     * never again.
     *
     * Repeating an explanation to someone who has read it is an obstacle on top
     * of what they are doing. Notices saying that something did **not** happen
     * stay always visible: they are the outcome of this attempt, not
     * instructions.
     *
     * The key is a hand-picked string, not the resource id: ids change with
     * every build, so users would get the notices again after every update.
     */
    private fun avvisoUnaVolta(ime: InputMethodService, chiave: String, resId: Int) {
        val prefs = ime.prefs()
        val pref = "cipher_visto_$chiave"
        if (prefs.getBoolean(pref, false)) return
        prefs.edit().putBoolean(pref, true).apply()
        toast(ime, resId)
    }

    /**
     * Messages to the user, through the path that actually works.
     *
     * **Not** `Toast.makeText`: since Android 13 a toast is suppressed if the
     * app's notifications are disabled, and a keyboard never asks for that
     * permission. The keyboard answered and nothing appeared on screen, as if
     * the key did nothing.
     *
     * `KeyboardSwitcher.showToast` picks by itself: a real toast up to Android
     * 12, then a notice drawn inside the keyboard window, which no permission
     * can suppress. HeliBoard had already solved this; its solution is reused.
     */
    private fun toast(ime: InputMethodService, resId: Int) {
        KeyboardSwitcher.getInstance().showToast(ime.getString(resId), true)
    }
}
