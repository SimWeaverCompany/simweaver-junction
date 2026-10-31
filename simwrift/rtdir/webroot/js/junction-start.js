/* ============================================================
   SimWeaver Junction - "Start gateway" dialog.
   One target per endpoint, prefilled with the configured default:
     HLA / Talk: the federation (typed, or picked from the federations
                 the endpoint's RTI reports - "Discover");
                 Talk also lists the channels of the chosen federation;
     DIS:        the exercise id 0-255 (picked from the exercises heard
                 on the endpoint's network for a moment - "Listen").
     HLA / Talk also: the LSD (RTI configuration name for HLA 4), prefilled
                 with the configured default; the other configured choices
                 are offered, and any other value can be typed (0.9.15).
   Usage: Rift.junctionStart.open(gatewayConfig, onStarted)
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jx = Rift.junction;

	var dialog, form, summary, varsHost, instanceHost, endpointsHost, result, submitBtn;
	var state = { cfg: null, onStarted: null, busy: false, inputs: {}, vars: null, instance: null };

	function isDis(ep) { return !!ep.isdis; }
	function isJ2j(ep) { return !!ep.isj2j; }
	/** what an endpoint's target is called */
	function noun(ep) { return isDis(ep) ? 'exercise' : isJ2j(ep) ? 'session' : 'federation'; }

	function validTarget(ep, v) {
		if (isDis(ep)) { return /^\d{1,3}$/.test(v) && Number(v) <= 255; }
		if (isJ2j(ep)) { return /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(v); }
		return v.length > 0;
	}

	function validate() {
		var ok = state.vars ? state.vars.validate() : true;
		ok = (state.instance ? state.instance.validate() : true) && ok;
		(state.cfg.endpoints || []).forEach(function (ep) {
			var f = state.inputs[ep.id];
			var v = f.input.value.trim();
			var good = validTarget(ep, v);
			f.input.classList.toggle('invalid', !good && f.touched);
			f.msg.classList.toggle('err', !good && f.touched);
			f.msg.textContent = good ? (isDis(ep) && v === '0' ? 'Exercise 0: PDUs of every exercise are received; sent PDUs use exercise 0.' : '')
				: (isDis(ep) ? 'An exercise id 0-255 is required.' : isJ2j(ep) ? 'A session name is required: 1-64 letters, digits, ".", "_" and "-".'
					: 'A federation name is required.');
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
		ui.clear(f.list).appendChild(ui.loadingRow(isDis(ep) ? 'Listening on the DIS network…' : isJ2j(ep) ? 'Asking the J2J network for its sessions…'
			: 'Asking the RTI for its federations…'));
		var req = jx.fetchTargets(state.cfg.id, ep.id, null, f.lsd ? f.lsd.value() : null).then(function (r) {
			if (token !== f.token) { return; }
			ui.clear(f.list);
			if (r.error) {
				f.list.appendChild(ui.errorRow(isDis(ep) ? 'Could not listen' : isJ2j(ep) ? 'Could not list sessions' : 'Could not list federations', new Error(r.error)));
				return;
			}
			if (r.targets.length === 0) {
				f.list.appendChild(ui.emptyBlock(isDis(ep) ? 'Nothing heard' : isJ2j(ep) ? 'No sessions yet' : 'No federations found',
					isDis(ep) ? 'No DIS PDUs arrived in 1.5 s. Type the exercise id.'
						: isJ2j(ep) ? 'No gateway in the J2J network has joined a session. Type a name: the first member starts the session.'
						: 'Nothing runs on this RTI right now. Type the federation name.'));
				return;
			}
			var dflt = String(ep.defaulttarget || '');
			f.list.appendChild(el('ul.fed-list', null, r.targets.map(function (t) {
				if (t && typeof t === 'object' && t.model !== undefined) {
					return option(f, String(t.id), t.id + '  · ' + (t.model || '?') + ' · ' + t.members + (t.members === 1 ? ' member' : ' members'),
						String(t.id) === dflt ? 'CONFIG' : null);
				}
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

	/** Talk: the channels of the federation in the input (join briefly). */
	function loadChannels(f) {
		var fed = f.input.value.trim();
		if (!fed || !f.channels) { return; }
		var token = ++f.chToken;
		ui.clear(f.channels).appendChild(ui.loadingRow('Joining ' + fed + ' to list its channels…'));
		jx.fetchTargets(state.cfg.id, f.ep.id, fed, f.lsd ? f.lsd.value() : null).then(function (r) {
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
			placeholder: isDis(ep) ? 'exercise id 0-255' : isJ2j(ep) ? 'session name' : 'federation name' });
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
		if (ep.ishla) {
			f.lsd = lsdBlock(ep, function () {
				ui.clear(f.list);                     // the federations listed were the other LSD's
				if (f.channels) { ui.clear(f.channels); }
			});
		}
		state.inputs[ep.id] = f;
		return el('section.jx-target', null, [
			el('div.jx-target-head', null, [
				el('span.jx-type.t-' + (badgeOf(ep)).toLowerCase(), { text: badgeOf(ep) }),
				el('label.field-label', { 'for': id }, [
					el('span', { text: (ep.name || ep.id) + ' · ' + noun(ep) }),
					el('span.req', { text: 'required' })
				])
			]),
			el('div.field-row', null, [f.input, f.reset, f.discoverBtn]),
			f.msg,
			f.list,
			f.lsd ? f.lsd.root : null,
			ep.istalk ? el('div.subhead', null, [el('span.subhead-title', { text: 'Channels in this federation' }),
				el('button.btn', { type: 'button', on: { click: function () { loadChannels(f); } } }, '⟳ List')]) : null,
			f.channels || null
		]);
	}

	function badgeOf(ep) {
		var m = { 'ieee1516-2010': 'HLA3', 'ieee1516-2025': 'HLA4', 'ieee1516-2025-fedpro': 'FEDPRO', dis6: 'DIS6', dis7: 'DIS7', cdis: 'C-DIS', talk: 'TALK', j2j: 'J2J' };
		return m[ep.standard] || String(ep.standard || '?').toUpperCase();
	}

	function submit(e) {
		e.preventDefault();
		if (state.busy || !validate()) { return; }
		var targets = {};
		(state.cfg.endpoints || []).forEach(function (ep) { targets[ep.id] = state.inputs[ep.id].input.value.trim(); });
		var vars = state.vars ? state.vars.values() : {};
		var instance = state.instance ? state.instance.value() : '';
		var lsds = {};
		(state.cfg.endpoints || []).forEach(function (ep) {
			var f = state.inputs[ep.id];
			if (f.lsd && f.lsd.changed()) { lsds[ep.id] = f.lsd.value(); }
		});
		state.busy = true;
		submitBtn.disabled = true;
		submitBtn.classList.add('busy');
		submitBtn.textContent = 'Joining…';
		ui.clear(result).appendChild(ui.loadingRow('Connecting the endpoints (HLA joins can take a while)…'));
		jx.startGateway(state.cfg.id, targets, vars, instance, lsds).then(function (r) {
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
		ui.clear(instanceHost);
		state.instance = instanceBlock(cfg.instances || [], cfg.nextinstance || '1', 'gateway', validate);
		instanceHost.appendChild(state.instance.root);
		ui.clear(varsHost);
		state.vars = null;
		varsHost.hidden = !(cfg.variables || []).length;
		if (!varsHost.hidden) {
			state.vars = variablesBlock(cfg.variables, cfg.group, validate, variableDiscovery([cfg], function (c, e) {
				var f = state.inputs[e];
				return f && f.lsd ? f.lsd.value() : null;
			}));
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
		instanceHost = document.getElementById('sd-instance');
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
	function variablesBlock(variables, group, onChange, discover) {
		var fields = [];
		var rows = (variables || []).map(function (v) {
			var id = 'var-' + v.name + '-' + Math.random().toString(36).slice(2, 7);
			var f = { v: v, touched: false };
			var d = v.description || '';
			f.input = el('input', { id: id, type: 'text', autocomplete: 'off', spellcheck: 'false',
				placeholder: v['default'] ? 'default: ' + v['default'] : d ? 'value' : 'value for ' + v.name });
			f.input.value = v.value || v['default'] || '';
			f.input.addEventListener('input', function () { f.touched = true; onChange(); });
			f.msg = el('div.field-msg', { 'aria-live': 'polite' });
			fields.push(f);
			var row = f.input;
			if (discover) {
				// 0.9.16: values from the federations (and DIS exercises) the endpoints' RTIs report
				f.found = el('div.jx-var-found', { 'aria-live': 'polite' });
				f.discBtn = el('button.btn.jx-var-disc', { type: 'button', title: 'List the federations the RTIs report, and the values of '
					+ v.name + ' they contain', on: { click: function () { discoverInto(f, discover, onChange); } } }, '⟳');
				row = el('div.field-row.jx-var-row', null, [f.input, f.discBtn]);
			}
			return f.cell = el('div.jx-var', null, [
				el('label.field-label', { 'for': id, title: v.name + (d ? ' · ' + d : '') }, [
					// 0.9.16: the description is the label; GROUPVARn stays visible beside it
					el('span' + (d ? '.jx-var-desc' : ''), { text: d || v.name }),
					d ? el('span.dim.jx-var-name', { text: ' ' + v.name }) : null,
					v['default'] ? el('span.dim', { text: ' default ' + v['default'] }) : el('span.req', { text: 'required' })
				]),
				row,
				f.msg,
				f.found || null
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

	/**
	 * The instance name of a start: prefilled with the next free number, checked against the
	 * instances that run. Shared with the group dialog.
	 * @param running names of the running instances
	 * @param suggestion the server's next free name
	 * @param what 'gateway' or 'group', for the texts
	 * @returns {{root, validate: function(): boolean, value: function(): string}}
	 */
	function instanceBlock(running, suggestion, what, onChange) {
		var id = 'inst-' + Math.random().toString(36).slice(2, 7);
		var touched = false;
		var input = el('input', { id: id, type: 'text', autocomplete: 'off', spellcheck: 'false', maxlength: '40',
			placeholder: 'next free number' });
		input.value = suggestion || '';
		input.addEventListener('input', function () { touched = true; onChange(); });
		var msg = el('div.field-msg', { 'aria-live': 'polite' });
		var NAME = /^[\p{L}\p{N} _.-]{1,40}$/u;
		var root = el('section.jx-instblock', null, [
			el('label.field-label', { 'for': id }, [el('span', { text: 'Instance' })]),
			input,
			msg,
			el('p.hint', { text: (running.length ? 'Running now: ' + running.join(', ') + '. ' : 'Nothing runs now. ') +
				'Several instances of a ' + what + ' can run at once, usually with other ' +
				(what === 'group' ? 'variable values' : 'targets or variable values') + '. Each needs a name of its own; ' +
				'empty takes the next free number.' })
		]);
		return {
			root: root,
			validate: function () {
				var v = input.value.trim();
				var why = !v ? '' : !NAME.test(v) ? 'Letters, digits, spaces, _ - . only (at most 40).'
					: running.indexOf(v) >= 0 ? 'Instance ' + v + ' is already running.' : '';
				input.classList.toggle('invalid', !!why);
				msg.classList.toggle('err', !!why);
				msg.textContent = why;
				return !why;
			},
			value: function () { return input.value.trim(); },
			focus: function () { input.focus(); }
		};
	}

	/**
	 * The LSD of an HLA endpoint (1516-2025: the RTI configuration name): prefilled with the
	 * configured default, the other configured choices as buttons, any other value typed.
	 * Only a value that differs from the default is sent, so group variables in the setting keep
	 * working. Shared with the group dialog.
	 * @param ep the endpoint of the configuration listing ({lsd, lsds, hlaversion, name, id})
	 * @param onChange called when the value changes
	 * @param title optional label text
	 * @returns {{root, value: function(): string, changed: function(): boolean}}
	 */
	function lsdBlock(ep, onChange, title) {
		var opts = Array.isArray(ep.lsds) && ep.lsds.length ? ep.lsds : [ep.lsd || ''];
		var dflt = opts[0];
		var hla4 = ep.hlaversion === 'IEEE 1516-2025';
		var id = 'lsd-' + Math.random().toString(36).slice(2, 7);
		var input = el('input', { id: id, type: 'text', autocomplete: 'off', spellcheck: 'false', maxlength: '512',
			placeholder: '(RTI default)' });
		input.value = dflt;
		var buttons = [];
		function mark() {
			var v = input.value.trim();
			buttons.forEach(function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-lsd') === v ? 'true' : 'false'); });
			input.classList.toggle('jx-changed', v !== dflt);
		}
		function set(v) { input.value = v; mark(); if (onChange) { onChange(); } }
		input.addEventListener('input', function () { mark(); if (onChange) { onChange(); } });
		var choices = opts.length > 1 ? el('ul.jx-lsd-choices', null, opts.map(function (o, i) {
			var b = el('button.fed-option', { type: 'button', 'data-lsd': o, 'aria-pressed': 'false',
				on: { click: function () { set(o); } } }, [
				el('span.fed-mark', { 'aria-hidden': 'true' }),
				el('span.fed-name', { text: o || '(RTI default)' }),
				i === 0 ? el('span.fed-tag', { text: 'DEFAULT' }) : null
			]);
			buttons.push(b);
			return el('li', null, b);
		})) : null;
		var reset = el('button.btn', { type: 'button', title: 'Use the configured default',
			on: { click: function () { set(dflt); } } }, '↺ Default');
		var root = el('div.jx-lsd-block', null, [
			el('label.field-label', { 'for': id }, [
				el('span', { text: title || ((hla4 ? 'RTI configuration' : 'LSD') + ' · ' + (ep.name || ep.id)) })
			]),
			el('div.field-row', null, [input, reset]),
			choices,
			el('p.hint', { text: (opts.length > 1 ? 'Pick one of the configured choices, or type another. ' : 'The configured value; type another to override it. ')
				+ 'Empty = the RTI\'s default.' })
		]);
		mark();
		return {
			root: root,
			value: function () { return input.value.trim(); },
			changed: function () { return input.value.trim() !== dflt; }
		};
	}

	/** Runs a variable's Discover and shows what it found as buttons that fill the field. */
	function discoverInto(f, discover, onChange) {
		var token = f.discToken = (f.discToken || 0) + 1;
		if (f.cell) { f.cell.classList.add('wide'); }      // the results get the whole width
		ui.clear(f.found).appendChild(ui.loadingRow('Asking the RTIs for their federations…'));
		var req = discover(f.v.name).then(function (r) {
			if (token !== f.discToken) { return; }
			ui.clear(f.found);
			function chip(value, title, tag) {
				return el('li', null, el('button.fed-option', { type: 'button', title: title, 'data-name': value,
					on: { click: function () { f.input.value = value; f.touched = true; onChange(); } } }, [
					el('span.fed-mark', { 'aria-hidden': 'true' }),
					el('span.fed-name', { text: value }),
					tag ? el('span.fed-tag', { text: tag }) : null
				]));
			}
			if (r.values.length) {
				f.found.appendChild(el('div.jx-var-found-head', { text: 'Values of ' + f.v.name + ' in the federations' }));
				f.found.appendChild(el('ul.jx-lsd-choices', null, r.values.map(function (x) {
					var from = x.from.filter(function (n) { return n !== x.value; });
					return chip(x.value, 'Found in ' + x.from.join(', '), from.length ? 'from ' + from.join(', ') : null);
				})));
			}
			var shown = {};
			r.values.forEach(function (x) { if (x.from.indexOf(x.value) >= 0) { shown[x.value] = true; } });
			var rest = r.all.filter(function (n) { return !shown[n]; });
			if (rest.length) {
				f.found.appendChild(el('div.jx-var-found-head', { text: r.matched
					? 'All federations (the whole name)' : 'Federations of the group\'s RTIs (the whole name)' }));
				f.found.appendChild(el('ul.jx-lsd-choices', null, rest.map(function (n) { return chip(n, 'Use the whole name ' + n, null); })));
			}
			if (!r.values.length && !rest.length && !r.errors.length) {
				f.found.appendChild(el('div.dim', { text: 'Nothing runs on the RTIs right now.' }));
			}
			r.errors.forEach(function (e) { f.found.appendChild(el('div.field-msg.err', { text: e })); });
		}, function (err) {
			if (token !== f.discToken) { return; }
			ui.clear(f.found).appendChild(ui.errorRow('Discovery failed', err));
		});
		return ui.busy(f.discBtn, req);
	}

	/**
	 * Discover for group variables (0.9.16): asks the RTI of every endpoint whose target uses the
	 * variable (each connection once) and takes the variable's value out of each federation name
	 * by the target as written: GROUPVAR1-Talk + Alpha-Talk gives Alpha. A variable in no target
	 * lists the federations of every HLA endpoint's RTI. The whole names are offered too.
	 * @param configs the configurations of the dialog (with endpoints[].targettemplate)
	 * @param lsdOf function (cfgId, epId) -> the LSD in the dialog, or null for the configured one
	 * @returns function (name) -> Promise<{values:[{value, from:[]}], all:[], errors:[], matched}>
	 */
	function variableDiscovery(configs, lsdOf) {
		return function (name) {
			var uses = [];
			(configs || []).forEach(function (c) {
				(c.endpoints || []).forEach(function (ep) {
					var t = ep.targettemplate;
					if (t && (t.match(/GROUPVAR\d+/g) || []).indexOf(name) >= 0) { uses.push({ cfg: c, ep: ep, tmpl: t }); }
				});
			});
			var probes = uses.length ? uses : [];
			if (!uses.length) {
				(configs || []).forEach(function (c) {
					(c.endpoints || []).forEach(function (ep) { if (ep.ishla) { probes.push({ cfg: c, ep: ep, tmpl: null }); } });
				});
			}
			var conns = {};
			probes.forEach(function (p) {
				var lsd = lsdOf ? lsdOf(p.cfg.id, p.ep.id) : null;
				var key = p.ep.isdis ? 'dis|' + (p.ep.disinterface || '') + '|' + p.ep.disport
					: 'hla|' + p.ep.standard + '|' + (p.ep.rtijar || '') + '|' + (p.ep.rtiaddress || '') + '|' + (lsd === null || lsd === undefined ? (p.ep.lsd || '') : lsd);
				(conns[key] = conns[key] || { cfg: p.cfg, ep: p.ep, lsd: lsd, tmpls: [] }).tmpls.push(p.tmpl);
			});
			return Promise.all(Object.keys(conns).map(function (k) {
				var c = conns[k];
				return jx.fetchTargets(c.cfg.id, c.ep.id, null, c.lsd).then(function (r) { return { c: c, r: r }; },
					function (err) { return { c: c, r: { error: err.message || String(err), targets: [] } }; });
			})).then(function (results) {
				var values = {}, order = [], all = [], seen = {}, errors = [];
				results.forEach(function (x) {
					if (x.r.error) { errors.push((x.c.ep.name || x.c.ep.id) + ': ' + x.r.error); }
					(x.r.targets || []).forEach(function (t) {
						var actual = t && typeof t === 'object' ? String(t.id) : String(t);
						if (!seen[actual]) { seen[actual] = true; all.push(actual); }
						x.c.tmpls.forEach(function (tm) {
							var v = tm ? valueIn(tm, name, actual) : null;
							if (v) {
								if (!values[v]) { values[v] = []; order.push(v); }
								if (values[v].indexOf(actual) < 0) { values[v].push(actual); }
							}
						});
					});
				});
				// the values found in the most federations first (GROUPVAR2 and GROUPVAR2-COMMS both give Outer)
				var ranked = order.map(function (v, i) { return { value: v, from: values[v], i: i }; });
				ranked.sort(function (a, b) { return b.from.length - a.from.length || a.i - b.i; });
				return { values: ranked.map(function (x) { return { value: x.value, from: x.from }; }), all: all, errors: errors, matched: uses.length > 0 };
			});
		};
	}

	/** The value of variable name in an actual target, by the target as written; null when it does not fit. */
	function valueIn(template, name, actual) {
		var groups = {}, n = 0, re = '^';
		template.split(/(GROUPVAR\d+)/).forEach(function (part) {
			if (/^GROUPVAR\d+$/.test(part)) {
				if (groups[part]) { re += '\\' + groups[part]; } else { n += 1; groups[part] = n; re += '(.+?)'; }
			} else {
				re += part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
			}
		});
		var m = new RegExp(re + '$').exec(actual);
		return m && groups[name] ? m[groups[name]] : null;
	}

	Rift.junctionStart = { open: open, variableDiscovery: variableDiscovery, valueIn: valueIn, close: close, variablesBlock: variablesBlock, instanceBlock: instanceBlock, lsdBlock: lsdBlock };
})(window, document);
