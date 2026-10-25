/* ============================================================
   SimWeaver Junktion - "Start gateway" dialog.
   One target per endpoint, prefilled with the configured default:
     HLA / Talk: the federation (typed, or picked from the federations
                 the endpoint's RTI reports - "Discover");
                 Talk also lists the channels of the chosen federation;
     DIS:        the exercise id 0-255 (picked from the exercises heard
                 on the endpoint's network for a moment - "Listen").
   Usage: Rift.junktionStart.open(gatewayConfig, onStarted)
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jx = Rift.junktion;

	var dialog, form, summary, varsHost, endpointsHost, result, submitBtn;
	var state = { cfg: null, onStarted: null, busy: false, inputs: {}, vars: null };

	function isDis(ep) { return !!ep.isdis; }

	function validTarget(ep, v) {
		if (isDis(ep)) { return /^\d{1,3}$/.test(v) && Number(v) <= 255; }
		return v.length > 0;
	}

	function validate() {
		var ok = state.vars ? state.vars.validate() : true;
		(state.cfg.endpoints || []).forEach(function (ep) {
			var f = state.inputs[ep.id];
			var v = f.input.value.trim();
			var good = validTarget(ep, v);
			f.input.classList.toggle('invalid', !good && f.touched);
			f.msg.classList.toggle('err', !good && f.touched);
			f.msg.textContent = good ? (isDis(ep) && v === '0' ? 'Exercise 0: PDUs of every exercise are received; sent PDUs use exercise 0.' : '')
				: (isDis(ep) ? 'An exercise id 0-255 is required.' : 'A federation name is required.');
			markPicked(f);
			ok = ok && good;
		});
		submitBtn.disabled = state.busy || !ok;
		return ok;
	}

	function markPicked(f) {
		var v = f.input.value.trim();
		Array.prototype.forEach.call(f.list.querySelectorAll('.fed-option'), function (b) {
			b.setAttribute('aria-pressed', b.getAttribute('data-name') === v ? 'true' : 'false');
		});
	}

	function pick(f, name) {
		if (state.busy) { return; }
		f.input.value = name;
		f.touched = true;
		validate();
		if (f.ep.istalk) { loadChannels(f); }
	}

	function option(f, id, label, tag) {
		return el('li', null, el('button.fed-option', {
			type: 'button', 'data-name': id, 'aria-pressed': 'false',
			on: { click: function () { pick(f, id); } }
		}, [
			el('span.fed-mark', { 'aria-hidden': 'true' }),
			el('span.fed-name', { text: label }),
			tag ? el('span.fed-tag', { text: tag }) : null
		]));
	}

	function discover(f) {
		var ep = f.ep;
		var token = ++f.token;
		ui.clear(f.list).appendChild(ui.loadingRow(isDis(ep) ? 'Listening on the DIS network…' : 'Asking the RTI for its federations…'));
		var req = jx.fetchTargets(state.cfg.id, ep.id).then(function (r) {
			if (token !== f.token) { return; }
			ui.clear(f.list);
			if (r.error) {
				f.list.appendChild(ui.errorRow(isDis(ep) ? 'Could not listen' : 'Could not list federations', new Error(r.error)));
				return;
			}
			if (r.targets.length === 0) {
				f.list.appendChild(ui.emptyBlock(isDis(ep) ? 'Nothing heard' : 'No federations found',
					isDis(ep) ? 'No DIS PDUs arrived in 1.5 s. Type the exercise id.' : 'Nothing runs on this RTI right now. Type the federation name.'));
				return;
			}
			var dflt = String(ep.defaulttarget || '');
			f.list.appendChild(el('ul.fed-list', null, r.targets.map(function (t) {
				if (t && typeof t === 'object') {
					return option(f, String(t.id), 'exercise ' + t.id + (t.pdus ? '  · ' + t.pdus + ' PDUs' : ''), String(t.id) === dflt ? 'CONFIG' : null);
				}
				return option(f, String(t), String(t), String(t) === dflt ? 'CONFIG' : null);
			})));
			markPicked(f);
		}, function (err) {
			if (token !== f.token) { return; }
			ui.clear(f.list).appendChild(ui.errorRow('Discovery failed', err));
		});
		return ui.busy(f.discoverBtn, req);
	}

	/** Pitch Talk: the channels of the federation in the input (join briefly). */
	function loadChannels(f) {
		var fed = f.input.value.trim();
		if (!fed || !f.channels) { return; }
		var token = ++f.chToken;
		ui.clear(f.channels).appendChild(ui.loadingRow('Joining ' + fed + ' to list its channels…'));
		jx.fetchTargets(state.cfg.id, f.ep.id, fed).then(function (r) {
			if (token !== f.chToken) { return; }
			ui.clear(f.channels);
			if (r.error) { f.channels.appendChild(ui.errorRow('Could not list channels', new Error(r.error))); return; }
			if (!r.targets.length) { f.channels.appendChild(el('div.dim', { text: 'No channels in ' + fed + ' (yet).' })); return; }
			f.channels.appendChild(el('div.jx-channels', null, r.targets.map(function (c) {
				return el('span.tag', { title: c.type || '', text: (c.type === 'RadioChannel' ? '📻 ' : c.type === 'VoiceChannel' ? '🎙 ' : '') + c.id });
			})));
		}, function (err) {
			if (token !== f.chToken) { return; }
			ui.clear(f.channels).appendChild(ui.errorRow('Could not list channels', err));
		});
	}

	function endpointBlock(ep) {
		var f = { ep: ep, touched: false, token: 0, chToken: 0 };
		var id = 'sd-t-' + ep.id;
		f.input = el('input', { id: id, type: 'text', autocomplete: 'off', spellcheck: 'false',
			placeholder: isDis(ep) ? 'exercise id 0-255' : 'federation name' });
		f.input.value = ep.defaulttarget === undefined || ep.defaulttarget === null ? '' : String(ep.defaulttarget);
		f.input.addEventListener('input', function () { f.touched = true; validate(); });
		f.input.addEventListener('change', function () { if (ep.istalk) { loadChannels(f); } });
		f.msg = el('div.field-msg', { 'aria-live': 'polite' });
		f.list = el('div.cd-fed-list');
		f.discoverBtn = el('button.btn', { type: 'button', on: { click: function () { discover(f); } } },
			isDis(ep) ? '◉ Listen' : '⟳ Discover');
		f.reset = el('button.btn', { type: 'button', title: 'Use the configured value',
			on: { click: function () { pick(f, String(ep.defaulttarget || '')); } } }, '↺ Config value');
		if (ep.istalk) { f.channels = el('div.jx-channel-list'); }
		state.inputs[ep.id] = f;
		return el('section.jx-target', null, [
			el('div.jx-target-head', null, [
				el('span.jx-type.t-' + (badgeOf(ep)).toLowerCase(), { text: badgeOf(ep) }),
				el('label.field-label', { 'for': id }, [
					el('span', { text: (ep.name || ep.id) + ' · ' + (isDis(ep) ? 'exercise' : 'federation') }),
					el('span.req', { text: 'required' })
				])
			]),
			el('div.field-row', null, [f.input, f.reset, f.discoverBtn]),
			f.msg,
			f.list,
			ep.istalk ? el('div.subhead', null, [el('span.subhead-title', { text: 'Channels in this federation' }),
				el('button.btn', { type: 'button', on: { click: function () { loadChannels(f); } } }, '⟳ List')]) : null,
			f.channels || null
		]);
	}

	function badgeOf(ep) {
		var m = { 'ieee1516-2010': 'HLA3', 'ieee1516-2025': 'HLA4', 'ieee1516-2025-fedpro': 'FEDPRO', dis6: 'DIS6', dis7: 'DIS7', talk: 'TALK' };
		return m[ep.standard] || String(ep.standard || '?').toUpperCase();
	}

	function submit(e) {
		e.preventDefault();
		if (state.busy || !validate()) { return; }
		var targets = {};
		(state.cfg.endpoints || []).forEach(function (ep) { targets[ep.id] = state.inputs[ep.id].input.value.trim(); });
		var vars = state.vars ? state.vars.values() : {};
		state.busy = true;
		submitBtn.disabled = true;
		submitBtn.classList.add('busy');
		submitBtn.textContent = 'Joining…';
		ui.clear(result).appendChild(ui.loadingRow('Connecting the endpoints (HLA joins can take a while)…'));
		jx.startGateway(state.cfg.id, targets, vars).then(function (r) {
			finish();
			close();
			if (state.onStarted) { state.onStarted(r); }
		}, function (err) {
			finish();
			ui.clear(result).appendChild(ui.errorRow('The gateway did not start', err));
			validate();
		});
	}

	function finish() {
		state.busy = false;
		submitBtn.classList.remove('busy');
		submitBtn.textContent = '▶ Start gateway';
	}

	function open(cfg, onStarted) {
		init();
		state.cfg = cfg;
		state.onStarted = onStarted;
		state.inputs = {};
		ui.clear(result);
		ui.clear(summary).appendChild(ui.kvList([
			{ label: 'GATEWAY', node: el('span.hl', { text: cfg.name || cfg.id }) },
			{ label: 'ROUTER', node: ui.valueNode(String((cfg.router || {}).kind || 'iem').toUpperCase() + ' · ' + ((cfg.router || {}).filtertext || 'all')) },
			{ label: 'FILE', node: el('code.path', { text: cfg.file || '' }) }
		]));
		ui.clear(varsHost);
		state.vars = null;
		varsHost.hidden = !(cfg.variables || []).length;
		if (!varsHost.hidden) {
			state.vars = variablesBlock(cfg.variables, cfg.group, validate);
			varsHost.appendChild(state.vars.root);
		}
		ui.clear(endpointsHost);
		(cfg.endpoints || []).forEach(function (ep) { endpointsHost.appendChild(endpointBlock(ep)); });
		validate();
		if (typeof dialog.showModal === 'function') { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
		var first = endpointsHost.querySelector('input');
		if (first) { first.focus(); }
	}

	function close() {
		if (state.busy) { return; }
		if (typeof dialog.close === 'function') { dialog.close(); } else { dialog.removeAttribute('open'); }
	}

	var ready = false;
	function init() {
		if (ready) { return; }
		ready = true;
		dialog = document.getElementById('start-dialog');
		form = document.getElementById('sd-form');
		summary = document.getElementById('sd-summary');
		varsHost = document.getElementById('sd-vars');
		endpointsHost = document.getElementById('sd-endpoints');
		result = document.getElementById('sd-result');
		submitBtn = document.getElementById('sd-submit');
		form.addEventListener('submit', submit);
		document.getElementById('sd-close').addEventListener('click', close);
		document.getElementById('sd-cancel').addEventListener('click', close);
		dialog.addEventListener('cancel', function (e) { if (state.busy) { e.preventDefault(); } });
	}

	/**
	 * Inputs for group variables (GROUPVAR1, ...): prefilled with the defaults, required where
	 * there is none. Shared with the group dialog.
	 * @returns {{root, validate: function(): boolean, values: function(): Object}}
	 */
	function variablesBlock(variables, group, onChange) {
		var fields = [];
		var rows = (variables || []).map(function (v) {
			var id = 'var-' + v.name + '-' + Math.random().toString(36).slice(2, 7);
			var f = { v: v, touched: false };
			f.input = el('input', { id: id, type: 'text', autocomplete: 'off', spellcheck: 'false',
				placeholder: v['default'] ? 'default: ' + v['default'] : 'value for ' + v.name });
			f.input.value = v.value || v['default'] || '';
			f.input.addEventListener('input', function () { f.touched = true; onChange(); });
			f.msg = el('div.field-msg', { 'aria-live': 'polite' });
			fields.push(f);
			return el('div.jx-var', null, [
				el('label.field-label', { 'for': id }, [
					el('span', { text: v.name }),
					v['default'] ? el('span.dim', { text: ' default ' + v['default'] }) : el('span.req', { text: 'required' })
				]),
				f.input,
				f.msg
			]);
		});
		var root = el('section.jx-varblock', null, [
			el('div.subhead', null, [el('span.subhead-title', { text: 'Group variables' + (group ? ' · ' + group : '') })]),
			el('p.hint', { text: 'Each GROUPVARn in the gateway, endpoint and router settings is replaced by its value.' }),
			el('div.jx-var-grid', null, rows)
		]);
		return {
			root: root,
			validate: function () {
				var ok = true;
				fields.forEach(function (f) {
					var good = f.input.value.trim().length > 0;
					f.input.classList.toggle('invalid', !good && f.touched);
					f.msg.classList.toggle('err', !good && f.touched);
					f.msg.textContent = good || !f.touched ? '' : 'A value is required.';
					ok = ok && good;
				});
				return ok;
			},
			values: function () {
				var out = {};
				fields.forEach(function (f) { var x = f.input.value.trim(); if (x) { out[f.v.name] = x; } });
				return out;
			},
			focus: function () { if (fields.length) { fields[0].input.focus(); } }
		};
	}

	Rift.junktionStart = { open: open, close: close, variablesBlock: variablesBlock };
})(window, document);
