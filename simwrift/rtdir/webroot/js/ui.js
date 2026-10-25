/* ============================================================
   SimWeaver Rift - shared UI helpers.
   DOM building (always via textContent, never innerHTML with
   server data), the Zulu clock, the link indicator and the
   standard loading / empty / error rows.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift = window.Rift || {};

	var MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

	/**
	 * Create an element.
	 * @param {string} tag   e.g. 'div' or 'span.tag.ok' (classes after dots)
	 * @param {Object} [attrs] attributes; 'text' sets textContent, 'on' = {event: fn}
	 * @param {Array|Node|string} [children]
	 */
	function el(tag, attrs, children) {
		var parts = tag.split('.');
		var node = document.createElement(parts[0]);
		if (parts.length > 1) { node.className = parts.slice(1).join(' '); }

		Object.keys(attrs || {}).forEach(function (key) {
			var val = attrs[key];
			if (val === undefined || val === null || val === false) { return; }
			if (key === 'text') { node.textContent = String(val); }
			else if (key === 'on') {
				Object.keys(val).forEach(function (ev) { node.addEventListener(ev, val[ev]); });
			}
			else if (key === 'class') { node.className += (node.className ? ' ' : '') + val; }
			else { node.setAttribute(key, val === true ? '' : String(val)); }
		});

		append(node, children);
		return node;
	}

	function append(node, children) {
		if (children === undefined || children === null) { return node; }
		if (!Array.isArray(children)) { children = [children]; }
		children.forEach(function (child) {
			if (child === undefined || child === null || child === false) { return; }
			node.appendChild(typeof child === 'string' || typeof child === 'number'
				? document.createTextNode(String(child)) : child);
		});
		return node;
	}

	function clear(node) {
		while (node.firstChild) { node.removeChild(node.firstChild); }
		return node;
	}

	function pad(n) { return (n < 10 ? '0' : '') + n; }

	/** "10:58:12Z" */
	function timeZ(date) {
		date = date || new Date();
		return pad(date.getUTCHours()) + ':' + pad(date.getUTCMinutes()) + ':' + pad(date.getUTCSeconds()) + 'Z';
	}

	/** Military date-time group, e.g. "141058Z OCT 26" */
	function dtg(date) {
		date = date || new Date();
		return pad(date.getUTCDate()) + pad(date.getUTCHours()) + pad(date.getUTCMinutes()) + 'Z ' +
			MONTHS[date.getUTCMonth()] + ' ' + String(date.getUTCFullYear()).slice(-2);
	}

	/**
	 * ISO time -> Zulu display: "11:43:12.345Z" for today (UTC), else "19 OCT 11:43:12Z".
	 * @param {string} iso
	 * @param {boolean} [ms] include milliseconds (default true for today)
	 */
	function zulu(iso, ms) {
		var t = Date.parse(iso);
		if (isNaN(t)) { return iso ? String(iso) : '\u2014'; }
		var d = new Date(t), now = new Date();
		var hms = pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds());
		var milli = '.' + ('00' + d.getUTCMilliseconds()).slice(-3);
		var sameDay = d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth() &&
			d.getUTCDate() === now.getUTCDate();
		if (sameDay) { return hms + (ms === false ? '' : milli) + 'Z'; }
		return pad(d.getUTCDate()) + ' ' + MONTHS[d.getUTCMonth()] + ' ' + hms + 'Z';
	}

	/** Starts the Zulu clock in the top bar (elements #clock-time and #clock-dtg). */
	function startClock() {
		var timeEl = document.getElementById('clock-time');
		var dtgEl = document.getElementById('clock-dtg');
		if (!timeEl) { return; }
		function tick() {
			var now = new Date();
			timeEl.textContent = timeZ(now);
			if (dtgEl) { dtgEl.textContent = dtg(now); }
		}
		tick();
		setInterval(tick, 1000);
	}

	/** Wires the top bar link indicator (#link-status) to API call results. */
	function startLinkIndicator() {
		var node = document.getElementById('link-status');
		if (!node || !Rift.api) { return; }
		Rift.api.onLinkChange(function (ok) {
			node.classList.toggle('ok', ok);
			node.classList.toggle('err', !ok);
			node.querySelector('.link-text').textContent = ok ? 'LINK UP' : 'LINK LOST';
			node.title = (ok ? 'Server reachable' : 'Server not reachable') + ' — last check ' + timeZ();
		});
	}

	/* ---------- Standard state rows ---------- */

	function loadingRow(text) {
		return el('div.state.state-loading', { role: 'status' }, [
			el('span.scan', { 'aria-hidden': 'true' }, [el('i'), el('i'), el('i'), el('i')]),
			el('span', { text: text || 'Acquiring…' })
		]);
	}

	/**
	 * Friendly "nothing here" block.
	 * @param {string} title
	 * @param {string} [hint]
	 */
	function emptyBlock(title, hint) {
		return el('div.state.state-empty', null, [
			el('span.empty-icon', { 'aria-hidden': 'true' }),
			el('div', null, [
				el('div.empty-title', { text: title }),
				hint ? el('div.empty-hint', { text: hint }) : null
			])
		]);
	}

	/**
	 * Error row with an optional retry action.
	 * @param {string} title
	 * @param {Error|*} err
	 * @param {Function} [onRetry]
	 */
	function errorRow(title, err, onRetry) {
		var detail = (err && err.message) ? err.message : String(err || '');
		var url = err && err.url ? err.url : null;
		var code = err && err.code ? String(err.code) : null;
		var status = err && err.status ? 'HTTP ' + err.status : null;
		return el('div.state.state-error', { role: 'alert' }, [
			el('span.err-tag', { text: 'FAULT' }),
			el('div.err-body', null, [
				el('div.err-title', { text: title }),
				el('div.err-detail', { title: (err && err.detail) || null }, detail),
				(code || status || url) ? el('div.err-meta', null, [
					code ? el('span.err-code', { text: code }) : null,
					status ? el('span', { text: status }) : null,
					url ? el('code', { text: url }) : null
				]) : null
			]),
			onRetry ? el('button.btn', { type: 'button', on: { click: onRetry } }, '⟳ Retry') : null
		]);
	}

	/* ---------- Generic value rendering ---------- */

	/** "hlafederatetype" -> "HLAFEDERATETYPE"; camelCase/snake_case get spaces. */
	function prettyKey(key) {
		return String(key)
			.replace(/([a-z0-9])([A-Z])/g, '$1 $2')
			.replace(/[_\-]+/g, ' ')
			.toUpperCase();
	}

	/** Renders any JSON value as a node: arrays -> tags, booleans -> ON/OFF. */
	function valueNode(value) {
		if (value === null || value === undefined || value === '') {
			return el('span.v-none', { text: '—' });
		}
		if (typeof value === 'boolean') {
			return el('span.flag.' + (value ? 'on' : 'off'), { text: value ? 'ON' : 'OFF' });
		}
		if (Array.isArray(value)) {
			if (value.length === 0) { return el('span.v-none', { text: 'none' }); }
			return el('span.tags', null, value.map(function (v) {
				return el('span.tag', { text: typeof v === 'object' ? JSON.stringify(v) : String(v) });
			}));
		}
		if (typeof value === 'object') {
			return el('code.v-json', { text: JSON.stringify(value) });
		}
		return el('span', { text: String(value) });
	}

	/**
	 * Key/value list as a <dl class="kv">.
	 * @param {Array<{label:string, node:Node, wide?:boolean}>} rows
	 */
	function kvList(rows) {
		var dl = el('dl.kv');
		rows.forEach(function (row) {
			dl.appendChild(el('dt', { text: row.label, class: row.wide ? 'wide' : null }));
			dl.appendChild(el('dd', { class: row.wide ? 'wide' : null }, row.node));
		});
		return dl;
	}

	/**
	 * Short notification in the lower right corner.
	 * @param {string} message
	 * @param {'ok'|'err'|'info'} [kind]
	 * @param {number} [ms] how long it stays (default 4 s)
	 */
	function toast(message, kind, ms) {
		var host = document.getElementById('toasts');
		if (!host) {
			host = el('div.toasts', { id: 'toasts', role: 'status', 'aria-live': 'polite' });
			document.body.appendChild(host);
		}
		kind = kind || 'info';
		var node = el('div.toast.' + kind, null, [
			el('span.toast-tag', { text: kind === 'ok' ? 'DONE' : kind === 'err' ? 'FAULT' : 'INFO' }),
			el('span', { text: message })
		]);
		host.appendChild(node);
		setTimeout(function () {
			node.classList.add('leaving');
			setTimeout(function () { if (node.parentNode) { node.parentNode.removeChild(node); } }, 300);
		}, ms || 4000);
	}

	/**
	 * Briefly highlights a node (live update). Alternates two animation names, which
	 * restarts the animation without forcing a synchronous layout of the page.
	 */
	function pulse(node) {
		if (!node) { return; }
		if (node.classList.contains('upd')) { node.classList.remove('upd'); node.classList.add('upd2'); }
		else { node.classList.remove('upd2'); node.classList.add('upd'); }
	}

	var FLASH_KEY = 'simweaver-rift-flash';

	/** Queues a toast to be shown on the next page (e.g. after a redirect). */
	function flash(message, kind) {
		try { window.sessionStorage.setItem(FLASH_KEY, JSON.stringify({ message: message, kind: kind || 'info' })); }
		catch (e) { /* storage blocked - message is lost, not critical */ }
	}

	/** Shows a toast queued with flash() on the previous page, if any. */
	function showFlash() {
		try {
			var raw = window.sessionStorage.getItem(FLASH_KEY);
			if (!raw) { return; }
			window.sessionStorage.removeItem(FLASH_KEY);
			var f = JSON.parse(raw);
			if (f && f.message) { toast(String(f.message), f.kind); }
		} catch (e) { /* ignore */ }
	}

	/** Marks a reload button busy while a promise is pending. */
	function busy(button, promise) {
		if (!button) { return promise; }
		button.disabled = true;
		button.classList.add('busy');
		var done = function () { button.disabled = false; button.classList.remove('busy'); };
		promise.then(done, done);
		return promise;
	}

	Rift.ui = {
		el: el,
		append: append,
		clear: clear,
		timeZ: timeZ,
		zulu: zulu,
		dtg: dtg,
		startClock: startClock,
		startLinkIndicator: startLinkIndicator,
		loadingRow: loadingRow,
		emptyBlock: emptyBlock,
		errorRow: errorRow,
		prettyKey: prettyKey,
		valueNode: valueNode,
		kvList: kvList,
		toast: toast,
		pulse: pulse,
		flash: flash,
		showFlash: showFlash,
		busy: busy
	};
})(window, document);
