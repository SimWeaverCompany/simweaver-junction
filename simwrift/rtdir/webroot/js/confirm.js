/* ============================================================
   SimWeaver Rift - confirmation dialog for destructive actions.
   Built in JS (no markup needed in the page), shared by all pages.
   The dialog stays open while the action runs and shows the error
   inside itself if the action fails, so the user can retry.

   Rift.confirmDialog.open({
     title:        'Remove datasource',
     tag:          'DEL',                       // small badge before the title
     subject:      'Sample1 - test1',           // highlighted name
     message:      'It will resign ... cannot be undone.',
     confirmLabel: '✕ Disconnect & remove',
     busyLabel:    'Disconnecting…',
     failTitle:    'Datasource was not removed',
     action:       function () { return promise; },   // required
     onDone:       function (result) { ... }          // after success
   });
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var dialog = null, parts = {}, current = null, busy = false;

	function build() {
		parts.tag = el('span.idx', { text: 'DEL' });
		parts.title = el('h2.title', { id: 'cf-title', text: 'Confirm' });
		parts.close = el('button.btn', { type: 'button', 'aria-label': 'Close', on: { click: close } }, '✕');
		parts.subject = el('div.cf-subject');
		parts.message = el('p.cf-message', { id: 'cf-message' });
		parts.result = el('div.cf-result');
		parts.cancel = el('button.btn', { type: 'button', on: { click: close } }, 'Cancel');
		parts.ok = el('button.danger', { type: 'submit' }, 'Confirm');

		var form = el('form', { novalidate: true, on: { submit: run } }, [
			el('div.panel-head', null, [parts.tag, parts.title, el('span.meta', null, parts.close)]),
			el('div.dialog-body', null, [
				el('div.cf-warning', null, [
					el('span.cf-glyph', { 'aria-hidden': 'true', text: '!' }),
					el('div', null, [parts.subject, parts.message])
				]),
				parts.result
			]),
			el('div.dialog-foot', null, [parts.cancel, parts.ok])
		]);

		dialog = el('dialog.dialog.dialog-danger', {
			id: 'confirm-dialog',
			role: 'alertdialog',
			'aria-labelledby': 'cf-title',
			'aria-describedby': 'cf-message'
		}, form);

		dialog.addEventListener('cancel', function (e) { if (busy) { e.preventDefault(); } });
		dialog.addEventListener('click', function (e) { if (e.target === dialog) { close(); } });
		document.body.appendChild(dialog);
	}

	function setBusy(on) {
		busy = on;
		parts.ok.disabled = on;
		parts.cancel.disabled = on;
		parts.close.disabled = on;
		parts.ok.classList.toggle('busy', on);
		parts.ok.textContent = on ? (current.busyLabel || 'Working…') : (current.confirmLabel || 'Confirm');
	}

	function run(e) {
		e.preventDefault();
		if (busy || !current) { return; }
		ui.clear(parts.result);
		setBusy(true);

		var opts = current;
		var promise;
		try { promise = Promise.resolve(opts.action()); }
		catch (err) { promise = Promise.reject(err); }

		promise.then(function (result) {
			setBusy(false);
			close();
			if (opts.onDone) { opts.onDone(result); }
		}, function (err) {
			setBusy(false);
			parts.result.appendChild(ui.errorRow(opts.failTitle || 'Action failed', err));
			parts.ok.focus();
		});
	}

	function open(opts) {
		if (!dialog) { build(); }
		if (busy) { return; }
		current = opts || {};

		parts.tag.textContent = current.tag || 'DEL';
		parts.title.textContent = current.title || 'Confirm';
		parts.subject.textContent = current.subject || '';
		parts.subject.hidden = !current.subject;
		parts.message.textContent = current.message || 'Are you sure?';
		ui.clear(parts.result);
		setBusy(false);

		if (typeof dialog.showModal === 'function') { dialog.showModal(); }
		else { dialog.setAttribute('open', ''); }

		// Safe default: Enter right after opening cancels instead of destroying.
		parts.cancel.focus();
	}

	function close() {
		if (busy || !dialog) { return; }
		if (typeof dialog.close === 'function') { dialog.close(); }
		else { dialog.removeAttribute('open'); }
	}

	Rift.confirmDialog = { open: open, close: close };
})(window, document);
