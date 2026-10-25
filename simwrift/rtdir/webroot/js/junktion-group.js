/* ============================================================
   SimWeaver Junktion - "Start group" dialog.
   Asks for the group variables (GROUPVAR1, ...) the group's gateways use,
   then starts every gateway of the group with those values and shows the
   outcome per gateway (one failing does not stop the others).
   Usage: Rift.junktionGroup.open(group, configsOfTheGroup, onDone)
     group: {"group","gateways":[ids],"variables":[{"name","default"?}],"running"}
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jx = Rift.junktion;

	var dialog, form, summary, varsHost, result, submitBtn;
	var state = { group: null, onDone: null, busy: false, vars: null, done: false };

	function validate() {
		var ok = state.vars ? state.vars.validate() : true;
		submitBtn.disabled = state.busy || !ok;
		return ok;
	}

	function submit(e) {
		e.preventDefault();
		if (state.done) { close(); return; }
		if (state.busy || !validate()) { return; }
		state.busy = true;
		submitBtn.disabled = true;
		submitBtn.classList.add('busy');
		submitBtn.textContent = 'Starting…';
		ui.clear(result).appendChild(ui.loadingRow('Starting ' + state.group.gateways.length + ' gateways…'));
		jx.startGroup(state.group.group, state.vars ? state.vars.values() : {}).then(function (r) {
			finish();
			showResults(r);
			if (state.onDone) { state.onDone(r); }
		}, function (err) {
			finish();
			ui.clear(result).appendChild(ui.errorRow('The group did not start', err));
			validate();
		});
	}

	function showResults(r) {
		ui.clear(result);
		var list = el('ul.jx-group-results', null, (r.results || []).map(function (x) {
			return el('li' + (x.success ? '.ok' : '.err'), null, [
				el('span.jx-gr-id', { text: x.configid }),
				el('span.jx-gr-what', { text: x.success ? 'running' : (x.error ? x.error.code + ': ' + x.error.message : 'failed') })
			]);
		}));
		result.appendChild(el('div.subhead', null, [el('span.subhead-title',
			{ text: (r.started || 0) + ' of ' + (r.total || 0) + ' started' })]));
		result.appendChild(list);
		state.done = true;
		submitBtn.disabled = false;
		submitBtn.textContent = 'Close';
	}

	function finish() {
		state.busy = false;
		submitBtn.classList.remove('busy');
		submitBtn.textContent = '▶ Start group';
	}

	function open(group, configs, onDone) {
		init();
		state.group = group;
		state.onDone = onDone;
		state.done = false;
		state.busy = false;
		finish();
		ui.clear(result);
		document.getElementById('gd-title').textContent = 'Start group ' + group.group;
		var names = (configs || []).map(function (c) { return c.name || c.id; });
		ui.clear(summary).appendChild(ui.kvList([
			{ label: 'GROUP', node: el('span.hl', { text: group.group }) },
			{ label: 'GATEWAYS', node: ui.valueNode(names.length ? names.join(' · ') : group.gateways.join(' · ')) },
			{ label: 'RUNNING', node: ui.valueNode(String(group.running || 0)) }
		]));
		ui.clear(varsHost);
		state.vars = null;
		if ((group.variables || []).length) {
			state.vars = Rift.junktionStart.variablesBlock(group.variables, null, validate);
			varsHost.appendChild(state.vars.root);
		} else {
			varsHost.appendChild(el('p.hint', { text: 'The gateways of this group use no group variables: each starts with its configured targets.' }));
		}
		validate();
		if (typeof dialog.showModal === 'function') { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
		if (state.vars) { state.vars.focus(); }
	}

	function close() {
		if (state.busy) { return; }
		if (typeof dialog.close === 'function') { dialog.close(); } else { dialog.removeAttribute('open'); }
	}

	var ready = false;
	function init() {
		if (ready) { return; }
		ready = true;
		dialog = document.getElementById('group-dialog');
		form = document.getElementById('gd-form');
		summary = document.getElementById('gd-summary');
		varsHost = document.getElementById('gd-vars');
		result = document.getElementById('gd-result');
		submitBtn = document.getElementById('gd-submit');
		form.addEventListener('submit', submit);
		document.getElementById('gd-close').addEventListener('click', close);
		document.getElementById('gd-cancel').addEventListener('click', close);
		dialog.addEventListener('cancel', function (e) { if (state.busy) { e.preventDefault(); } });
	}

	Rift.junktionGroup = { open: open, close: close };
})(window, document);
