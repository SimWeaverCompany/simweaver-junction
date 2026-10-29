/* ============================================================
   SimWeaver Rift - server info in the top bar
   GET /api/serverinfo every 5 s (paused while the tab is hidden):
   version, process CPU (% of the machine) and heap memory, with the
   details in the tooltip. Hidden when the server has no such endpoint.
   Also shows the J2J button (#nav-j2j) while J2J is configured.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift = window.Rift || {};
	var el = Rift.ui.el;

	var PERIOD_MS = 5000;
	var WARN = 70, ALERT = 90;      // percent: amber, red

	function mb(bytes) {
		if (bytes === null || bytes === undefined || isNaN(bytes)) { return '—'; }
		var m = bytes / 1048576;
		return m >= 10240 ? (m / 1024).toFixed(1) + ' GB' : Math.round(m) + ' MB';
	}

	function pct(v) {
		return v === null || v === undefined || isNaN(v) ? '—' : (v < 10 ? v.toFixed(1) : Math.round(v)) + '%';
	}

	function uptime(s) {
		if (s === null || s === undefined || isNaN(s)) { return '—'; }
		var d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
		return d ? d + 'd ' + h + 'h' : h ? h + 'h ' + m + 'm' : m + 'm';
	}

	function level(p) {
		return p === null || p === undefined || isNaN(p) ? '' : p >= ALERT ? 'alert' : p >= WARN ? 'warn' : '';
	}

	/** One instrument cell: label, value and a thin meter (0-100). */
	function cell(label) {
		var value = el('span.si-v', { text: '—' });
		var fill = el('span.si-fill');
		var node = el('span.si-cell', null, [
			el('span.si-k', { text: label }), value, el('span.si-meter', { 'aria-hidden': 'true' }, fill)
		]);
		return {
			node: node,
			set: function (text, percent) {
				value.textContent = text;
				var p = percent === null || percent === undefined || isNaN(percent) ? 0 : Math.max(0, Math.min(100, percent));
				fill.style.width = p + '%';
				node.className = 'si-cell' + (level(percent) ? ' ' + level(percent) : '');
			}
		};
	}

	function ServerInfo(host) {
		this.host = host;
		this.ver = el('span.si-ver', { text: '—' });
		this.cpu = cell('CPU');
		this.mem = cell('MEM');
		host.appendChild(this.ver);
		host.appendChild(this.cpu.node);
		host.appendChild(this.mem.node);
		this.timer = null;
		this.busy = false;
		this.stopped = false;
	}

	ServerInfo.prototype.start = function () {
		var self = this;
		document.addEventListener('visibilitychange', function () {
			if (!document.hidden) { self.poll(); }
		});
		this.poll();
	};

	ServerInfo.prototype.schedule = function () {
		var self = this;
		clearTimeout(this.timer);
		if (!this.stopped) { this.timer = setTimeout(function () { self.poll(); }, PERIOD_MS); }
	};

	ServerInfo.prototype.poll = function () {
		var self = this;
		if (this.stopped || this.busy) { return; }
		if (document.hidden) { this.schedule(); return; }
		this.busy = true;
		Rift.api.getJson(Rift.api.endpoints.serverInfo(), { timeoutMs: 4000 }).then(function (reply) {
			self.busy = false;
			var info = reply && (reply.serverinfo || reply);
			if (!info || typeof info !== 'object') { throw new Error('no serverinfo'); }
			self.render(info);
			self.schedule();
		}, function (err) {
			self.busy = false;
			if (err && err.status === 404) {           // an older backend: no such call, no readout
				self.stopped = true;
				self.host.hidden = true;
				return;
			}
			self.host.classList.add('stale');           // keep the last values, dimmed
			self.schedule();
		});
	};

	ServerInfo.prototype.render = function (i) {
		var cpu = i.cpu || {}, mem = i.memory || {};
		var heapPct = mem.heapmax ? 100 * mem.heapused / mem.heapmax : null;
		this.ver.textContent = i.version ? 'v' + i.version : '—';
		this.cpu.set(pct(cpu.process), cpu.process);
		this.mem.set(mb(mem.heapused), heapPct);
		this.host.classList.remove('stale');
		this.host.hidden = false;
		showJ2j(i.j2j);
		this.host.title = [
			(i.name || 'Server') + ' ' + (i.version || ''),
			'CPU ' + pct(cpu.process) + ' of ' + (cpu.cores || '?') + ' cores' +
				(cpu.system !== null && cpu.system !== undefined ? '  (machine ' + pct(cpu.system) + ')' : ''),
			'Heap ' + mb(mem.heapused) + ' / ' + mb(mem.heapmax) + (heapPct !== null ? ' (' + Math.round(heapPct) + '%)' : '') +
				'  non-heap ' + mb(mem.nonheapused),
			mem.processcommitted ? 'Process committed ' + mb(mem.processcommitted) : null,
			'Threads ' + (i.threads != null ? i.threads : '—') + '  datasources ' + (i.datasources != null ? i.datasources : '—') +
				'  up ' + uptime(i.uptimeseconds),
			[i.java ? 'Java ' + i.java : null, i.os || null].filter(Boolean).join('  '),
			syslogLine(i.syslog)
		].filter(Boolean).join('\n');
	};

	/* 0.10.0: the J2J button in the top bar is shown only while J2J is configured (running or error) */
	function showJ2j(j) {
		var nav = document.getElementById('nav-j2j');
		if (!nav) return;
		var state = j && j.state ? j.state : 'off';
		nav.hidden = state === 'off';
		nav.classList.toggle('nav-warn', state === 'error');
		nav.title = state === 'error' ? 'SimWeaver J2J - J2J did not start: see the J2J page'
			: 'SimWeaver J2J - the network of Junction servers';
	}

	/* 0.9.22: "Syslog udp 10.0.0.9:514 (RFC 5424): 1520 sent" / "... not reachable: <error>, 12 queued" */
	function syslogLine(s) {
		if (!s || !s.enabled) return null;
		var line = 'Syslog ' + s.protocol + ' ' + s.target + ' (' + (s.format === 'rfc3164' ? 'RFC 3164' : 'RFC 5424') + '): ';
		if (s.connected) {
			line += s.sent + ' sent';
		} else {
			line += 'not reachable' + (s.lasterror ? ': ' + s.lasterror : '') + ', ' + s.queued + ' queued';
		}
		if (s.dropped) line += ', ' + s.dropped + ' dropped';
		return line;
	}

	var host = document.getElementById('server-info');
	if (host && Rift.api && Rift.api.endpoints.serverInfo) {
		Rift.serverInfo = new ServerInfo(host);
		Rift.serverInfo.start();
	}
})(window, document);
