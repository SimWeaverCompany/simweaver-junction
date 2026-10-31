/* ============================================================
   SimWeaver Rift - "Create datasource" dialog.
   Opened from a configuration card. HLA: the federation can be typed
   in manually (never empty) or picked from the federations the
   server discovers for the config's standard + LSD. DIS: the exercise
   id (0-255, 0 = all exercises) is sent in place of the federation.
   Usage: Rift.createDialog.open(config, onCreated)
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var dialog, form, input, msg, list, discMeta, result, submitBtn, rescanBtn, resetBtn, fedSection, summary, fedLabel, discBlock;
	var lsdSection, lsdInput, lsdChoices, lsdHint, lsdLabel, lsdReset;

	var state = {
		config: null,
		onCreated: null,
		busy: false,
		touched: false,
		discovered: [],
		fetchToken: 0,
		lsdDefault: ''
	};

	/* ---------- LSD (0.9.15): the configured default, other choices, or a typed value ---------- */

	function isHla(cfg) { return !!cfg && !isDis(cfg) && ('hlafederation' in cfg || 'hlalsd' in cfg); }
	function lsdOptions(cfg) {
		return Array.isArray(cfg.hlalsds) && cfg.hlalsds.length ? cfg.hlalsds : [cfg.hlalsd || ''];
	}
	function lsdValue() { return lsdInput.value.trim(); }
	function lsdLabelOf(v) { return v ? v : '(RTI default)'; }

	function renderLsd(cfg) {
		var hla = isHla(cfg);
		lsdSection.hidden = !hla;
		if (!hla) { return; }
		var opts = lsdOptions(cfg);
		state.lsdDefault = opts[0];
		lsdInput.value = opts[0];
		lsdLabel.textContent = /2025/.test(String(cfg.standard)) ? 'RTI configuration' : 'LSD';
		ui.clear(lsdChoices);
		if (opts.length > 1) {
			opts.forEach(function (o, i) {
				lsdChoices.appendChild(el('li', null, el('button.fed-option', {
					type: 'button', 'data-lsd': o, 'aria-pressed': 'false',
					on: { click: function () { lsdInput.value = o; lsdChanged(true); } }
				}, [
					el('span.fed-mark', { 'aria-hidden': 'true' }),
					el('span.fed-name', { text: lsdLabelOf(o) }),
					i === 0 ? el('span.fed-tag', { text: 'DEFAULT' }) : null
				])));
			});
		}
		lsdHint.textContent = (opts.length > 1 ? 'Pick one of the configured choices, or type another. ' : 'The configured value; type another to override it. ')
			+ 'Empty = the RTI\'s default.';
		markLsd();
	}

	function markLsd() {
		var v = lsdValue();
		Array.prototype.forEach.call(lsdChoices.querySelectorAll('.fed-option'), function (b) {
			b.setAttribute('aria-pressed', b.getAttribute('data-lsd') === v ? 'true' : 'false');
		});
		lsdInput.classList.toggle('changed', v !== state.lsdDefault);
		if (state.config && !isDis(state.config)) {
			discMeta.textContent = String(state.config.standard || '?').replace(/^IEEE(\d+)_(\d+)$/i, 'IEEE $1-$2') + ' · LSD ' + lsdLabelOf(v);
		}
	}

	/** @param rescan scan again at once (a choice was picked); typing only invalidates the list */
	function lsdChanged(rescan) {
		markLsd();
		ui.clear(result);
		if (rescan) { loadFederations(); return; }
		state.fetchToken++;
		ui.clear(list).appendChild(ui.emptyBlock('LSD changed', 'Rescan to list the federations on this LSD.'));
	}

	function isDis(cfg) { return Rift.configCard.isDis(cfg); }
	/** True when the dialog asks for a value: an HLA federation, or a DIS exercise id. */
	function hasFederation(cfg) { return !!cfg && (isDis(cfg) || ('hlafederation' in cfg)); }
	/** The configuration's own value: hlafederation, or disexid for DIS. */
	function configValue(cfg) {
		var v = isDis(cfg) ? cfg.disexid : cfg.hlafederation;
		return v === undefined || v === null ? '' : String(v);
	}
	function value() { return input.value.trim(); }
	function isValid() {
		if (!hasFederation(state.config)) { return true; }
		if (isDis(state.config)) { return /^\d{1,3}$/.test(value()) && Number(value()) <= 255; }
		return value().length > 0;
	}

	/* ---------- Validation ---------- */

	function validate() {
		var ok = isValid();
		submitBtn.disabled = state.busy || !ok;
		if (!hasFederation(state.config)) { return ok; }

		input.classList.toggle('invalid', !ok && state.touched);
		input.setAttribute('aria-invalid', (!ok && state.touched) ? 'true' : 'false');
		msg.classList.toggle('err', !ok && state.touched);
		var dis = isDis(state.config);
		msg.textContent = ok
			? (input.value !== value() ? 'Leading and trailing spaces will be removed.'
				: (dis ? (value() === '0' ? 'Exercise 0: PDUs of every exercise are received.' : '') : ''))
			: (dis
				? (state.touched ? 'An exercise id 0-255 is required (0 = all exercises).' : 'Enter the DIS exercise id, 0-255 (0 = all exercises).')
				: (state.touched ? 'A federation name is required.' : 'Enter a federation name, or pick one below.'));
		markSelected();
		return ok;
	}

	/* ---------- Discovered federations ---------- */

	function markSelected() {
		var current = value();
		Array.prototype.forEach.call(list.querySelectorAll('.fed-option'), function (btn) {
			btn.setAttribute('aria-pressed', btn.getAttribute('data-name') === current ? 'true' : 'false');
		});
	}

	function pick(name) {
		if (state.busy) { return; }
		input.value = name;
		state.touched = true;
		validate();
		input.focus();
	}

	function renderFederations(names) {
		var cfgDefault = String(state.config.hlafederation || '');
		ui.clear(list);
		if (names.length === 0) {
			list.appendChild(ui.emptyBlock(
				'No federations found',
				'Nothing is running on this LSD right now. Enter the federation name manually.'));
			return;
		}
		list.appendChild(el('ul.fed-list', null, names.map(function (name) {
			return el('li', null, el('button.fed-option', {
				type: 'button',
				'data-name': name,
				'aria-pressed': 'false',
				title: 'Use federation “' + name + '”',
				on: { click: function () { pick(name); } }
			}, [
				el('span.fed-mark', { 'aria-hidden': 'true' }),
				el('span.fed-name', { text: name }),
				name === cfgDefault ? el('span.fed-tag', { text: 'CONFIG' }) : null
			]));
		})));
		markSelected();
	}

	function loadFederations() {
		var cfg = state.config;
		var token = ++state.fetchToken;
		ui.clear(list).appendChild(ui.loadingRow('Scanning LSD for federations…'));

		var req = Rift.api.fetchAutoFederations(cfg.id, cfg.standard, isHla(cfg) ? lsdValue() : cfg.hlalsd).then(function (names) {
			if (token !== state.fetchToken) { return; }
			state.discovered = names;
			renderFederations(names);
		}, function (err) {
			if (token !== state.fetchToken) { return; }
			ui.clear(list).appendChild(err.status === 404
				? ui.emptyBlock('Federation discovery not available',
					'The server does not offer a federation list yet. Enter the federation name manually.')
				: ui.errorRow('Could not scan for federations', err, loadFederations));
		});
		return ui.busy(rescanBtn, req);
	}

	/* ---------- Summary of the chosen configuration ---------- */

	function renderSummary(cfg) {
		ui.clear(summary);
		var rows = [];
		if (cfg.standardlabel || cfg.standard) {
			rows.push({ label: 'STANDARD', node: ui.valueNode(cfg.standardlabel || cfg.standard) });
		}
		if (isDis(cfg)) {
			rows.push({ label: 'INTERFACE', node: ui.valueNode(cfg.disinterface ? cfg.disinterface : 'all interfaces') });
			if ('disport' in cfg) { rows.push({ label: 'UDP PORT', node: ui.valueNode(cfg.disport) }); }
			if ('disentitytimeoutsecs' in cfg) { rows.push({ label: 'ENTITY TIMEOUT', node: ui.valueNode(cfg.disentitytimeoutsecs + ' s') }); }
		} else {
			if ('hlalsd' in cfg) {
				var lo = lsdOptions(cfg);
				rows.push({ label: 'LSD', node: ui.valueNode(lsdLabelOf(lo[0]) + (lo.length > 1 ? '  (+' + (lo.length - 1) + ' more to choose from)' : '')) });
			}
			if ('hlafederatetype' in cfg) { rows.push({ label: 'FEDERATE TYPE', node: ui.valueNode(cfg.hlafederatetype) }); }
		}
		if (Array.isArray(cfg.hlafoms)) {
			rows.push({ label: isDis(cfg) ? 'REFERENCE FOM' : 'FOM MODULES',
				node: ui.valueNode(cfg.hlafoms.length + ' module' + (cfg.hlafoms.length === 1 ? '' : 's')) });
		}
		summary.appendChild(el('div.cd-config', null, [
			el('span.dim', { text: 'FROM CONFIG' }),
			el('span.cd-config-name', { text: cfg.name || '(unnamed)' }),
			cfg.file ? el('span.dim', { text: cfg.file }) : null
		]));
		summary.appendChild(ui.kvList(rows));
	}

	/* ---------- Busy / submit ---------- */

	function setBusy(busy) {
		state.busy = busy;
		input.disabled = busy;
		resetBtn.disabled = busy;
		lsdInput.disabled = busy;
		lsdReset.disabled = busy;
		Array.prototype.forEach.call(lsdChoices.querySelectorAll('button'), function (b) { b.disabled = busy; });
		rescanBtn.disabled = busy;
		Array.prototype.forEach.call(list.querySelectorAll('button'), function (b) { b.disabled = busy; });
		submitBtn.classList.toggle('busy', busy);
		submitBtn.textContent = busy ? 'Creating…' : '▶ Create datasource';
		validate();
	}

	function submit(e) {
		e.preventDefault();
		if (state.busy) { return; }
		state.touched = true;
		if (!validate()) { input.focus(); return; }

		var cfg = state.config;
		var federation = hasFederation(cfg) ? value() : undefined;
		ui.clear(result);
		setBusy(true);

		var lsd = isHla(cfg) && lsdValue() !== state.lsdDefault ? lsdValue() : undefined;
		Rift.api.createDatasource(cfg.id, federation, lsd).then(function (created) {
			setBusy(false);
			close();
			var label = (created && created.name) ? created.name : (cfg.name || 'datasource');
			var where = !federation ? '' : (isDis(cfg) ? ' for exercise ' + (federation === '0' ? 'all' : federation) : ' in federation ' + federation);
			ui.toast('Datasource ' + label + ' created' + where + '.', 'ok');
			if (state.onCreated) { state.onCreated(created, cfg, federation); }
		}, function (err) {
			setBusy(false);
			result.appendChild(ui.errorRow('Datasource was not created', err));
		});
	}

	/* ---------- Open / close ---------- */

	function open(cfg, onCreated) {
		state.config = cfg;
		state.onCreated = onCreated || null;
		state.touched = false;
		state.discovered = [];

		document.getElementById('cd-title').textContent = 'Create Datasource · ' + (cfg.name || '');
		renderSummary(cfg);
		ui.clear(result);
		renderLsd(cfg);

		var withFed = hasFederation(cfg);
		var dis = isDis(cfg);
		fedSection.hidden = !withFed;
		fedLabel.textContent = dis ? 'Exercise ID' : 'Federation';
		input.placeholder = dis ? '0-255 (0 = all exercises)' : 'federation name';
		input.setAttribute('inputmode', dis ? 'numeric' : 'text');
		resetBtn.title = dis ? 'Use the exercise id from the configuration' : 'Use the federation from the configuration';
		discBlock.hidden = dis;
		if (withFed) {
			input.value = configValue(cfg);
		}
		if (withFed && !dis) {
			markLsd();
			loadFederations();
		} else {
			state.fetchToken++;   // DIS has no federations to scan; ignore any scan still on its way
			ui.clear(list);
		}
		setBusy(false);

		if (typeof dialog.showModal === 'function') { dialog.showModal(); }
		else { dialog.setAttribute('open', ''); }

		if (withFed) { input.focus(); input.select(); } else { submitBtn.focus(); }
	}

	function close() {
		if (state.busy) { return; }
		state.fetchToken++;
		if (typeof dialog.close === 'function') { dialog.close(); }
		else { dialog.removeAttribute('open'); }
	}

	document.addEventListener('DOMContentLoaded', function () {
		dialog     = document.getElementById('create-dialog');
		form       = document.getElementById('cd-form');
		input      = document.getElementById('cd-federation');
		msg        = document.getElementById('cd-fed-msg');
		list       = document.getElementById('cd-fed-list');
		discMeta   = document.getElementById('cd-disc-meta');
		result     = document.getElementById('cd-result');
		submitBtn  = document.getElementById('cd-submit');
		rescanBtn  = document.getElementById('cd-rescan');
		resetBtn   = document.getElementById('cd-reset');
		fedSection = document.getElementById('cd-fed-section');
		summary    = document.getElementById('cd-summary');
		fedLabel   = document.getElementById('cd-fed-label');
		discBlock  = document.getElementById('cd-disc');
		lsdSection = document.getElementById('cd-lsd-section');
		lsdInput   = document.getElementById('cd-lsd');
		lsdChoices = document.getElementById('cd-lsd-choices');
		lsdHint    = document.getElementById('cd-lsd-hint');
		lsdLabel   = document.getElementById('cd-lsd-label');
		lsdReset   = document.getElementById('cd-lsd-reset');
		lsdInput.addEventListener('input', function () { lsdChanged(false); });
		lsdReset.addEventListener('click', function () { lsdInput.value = state.lsdDefault; lsdChanged(true); });

		form.addEventListener('submit', submit);
		input.addEventListener('input', function () { state.touched = true; ui.clear(result); validate(); });
		input.addEventListener('blur', function () { state.touched = true; validate(); });
		rescanBtn.addEventListener('click', loadFederations);
		resetBtn.addEventListener('click', function () { pick(configValue(state.config)); });
		document.getElementById('cd-cancel').addEventListener('click', close);
		document.getElementById('cd-close').addEventListener('click', close);

		// Esc closes natively - but not while a create request is pending.
		dialog.addEventListener('cancel', function (e) { if (state.busy) { e.preventDefault(); } });
		// Click on the backdrop closes the dialog.
		dialog.addEventListener('click', function (e) { if (e.target === dialog) { close(); } });
	});

	Rift.createDialog = { open: open, close: close };
})(window, document);
