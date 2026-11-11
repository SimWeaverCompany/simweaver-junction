/* ============================================================
   SimWeaver Rift - the Comms page (comms.html?id=<datasource>), 0.12.0.

   The communication channels a datasource sees:
     - Talk: voice, radio and chat channels and the lobby; senders are
       the Talk clients
     - RPR / DIS radio: one channel per frequency, with the transmitter
       objects on it and the platforms that host them; signals without
       audio (raw binary, Link 16, ...) show as activity only
   Each channel has a two-minute activity graph. Chat channels have a
   transcript (panel 02). Audio channels can be ticked to listen
   (js/comms-audio.js).

   Data: GET /api/datasources/{id}/comms (snapshot), live over SSE topic
   comms:{id} (comms.tick once a second, comms.chat per message) and
   ds:{id} (state, removal); GET .../comms/chat?channel= for a transcript.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var dsid = new URLSearchParams(window.location.search).get('id');
	var SECONDS = 120;
	var KIND_LABEL = { voice: 'VOICE', radio: 'RADIO', chat: 'CHAT', lobby: 'LOBBY' };
	var STATUS_TEXT = { 0: 'OFF', 1: 'ON', 2: 'TX' };

	var channels = {};            // id -> channel (snapshot JSON, activity kept live)
	var rows = {};                // id -> row elements
	var rev = -1, lastT = 0;
	var listening = {};           // id -> true
	var levels = {};              // id -> 0..1 (decays)
	var chatOpen = null, chatLines = [], chatSeq = 0;
	var audio = null, reloadTimer = null, streamLive = false, removed = false;
	/** 0.12.1: Opus is decoded by the browser (WebCodecs): null until known, then true / false. */
	var opusOk = null;
	var OPUS_NO = 'This browser cannot decode Opus (WebCodecs is needed: a current Edge or Chrome, Firefox 130 or later)';

	function $(id) { return document.getElementById(id); }

	function simple(q) { return q ? String(q).split('.').pop() : ''; }

	function ago(iso) {
		if (!iso) { return '—'; }
		var s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
		if (s < 60) { return s + 's'; }
		if (s < 3600) { return Math.floor(s / 60) + 'm'; }
		return Math.floor(s / 3600) + 'h';
	}

	function hz(f) {
		if (!f) { return ''; }
		if (f >= 1e9) { return trimNum(f / 1e9) + ' GHz'; }
		if (f >= 1e6) { return trimNum(f / 1e6) + ' MHz'; }
		if (f >= 1e3) { return trimNum(f / 1e3) + ' kHz'; }
		return f + ' Hz';
	}

	function trimNum(v) { return String(Math.round(v * 10000) / 10000); }

	function dsLink(instanceid) {
		return 'datasource.html?id=' + encodeURIComponent(dsid) + '&focus=' + encodeURIComponent(instanceid);
	}

	/* ---------- header ---------- */

	var STATE_CLASS = { JOINED: 'ok', JOINING: 'warn', CONNECTING: 'warn', RECONNECTING: 'warn', DISCONNECTED: 'err', ERROR: 'err', CONNECTION_LOST: 'err' };

	function setState(state, message) {
		var n = $('cm-state');
		n.textContent = state || '—';
		n.className = 'ds-state ' + (STATE_CLASS[state] || '');
		n.title = message || '';
	}

	function message(kind, tag, text) {
		var box = ui.clear($('cm-msg'));
		if (text) {
			box.appendChild(el('div.ds-statemsg.' + kind, null, [el('span.err-tag', { text: tag }), el('span', { text: text })]));
		}
	}

	function loadHeader() {
		Rift.api.getJson(Rift.api.endpoints.datasource(dsid)).then(function (reply) {
			var ds = reply && reply.datasource ? reply.datasource : reply || {};
			var name = ds.name ? String(ds.name) : 'Datasource ' + dsid;
			$('cm-title').textContent = name;
			document.title = 'Comms · ' + name + ' · SimWeaver Rift';
			setState(ds.state, ds.statemessage);
		}, function (err) {
			if (err && err.status === 404) { gone(); }
		});
	}

	function gone() {
		removed = true;
		setState('REMOVED', 'The datasource was removed');
		message('err', 'GONE', 'This datasource was removed. Its channels are no longer updated.');
		if (audio) { audio.stop(); }
	}

	function setStream(status) {
		var box = $('cm-stream');
		box.querySelector('.lamp').className = 'lamp' + (status === 'live' ? '' : status === 'lost' ? ' err' : ' off');
		box.querySelector('.ds-stream-text').textContent = status === 'live' ? 'LIVE' : status === 'lost' ? 'STREAM LOST · RETRYING' : 'CONNECTING';
		box.className = 'ds-stream ' + status;
		streamLive = status === 'live';
	}

	function facts() {
		var ids = Object.keys(channels);
		var talk = 0, radio = 0, tx = 0, active = 0;
		ids.forEach(function (id) {
			var c = channels[id];
			if (c.family === 'talk') { talk++; } else { radio++; }
			(c.sources || []).forEach(function (s) { if (s.kind === 'transmitter' && s.object) { tx++; } });
			if (sum(c.activity, 10) > 0) { active++; }
		});
		var f = ui.clear($('cm-facts'));
		[['TALK', talk], ['RADIO', radio], ['TRANSMITTERS', tx], ['ACTIVE 10S', active]].forEach(function (p) {
			f.appendChild(el('span', null, [el('span.fact-k', { text: p[0] }), el('span.fact-v', { text: String(p[1]) })]));
		});
	}

	function sum(a, last) {
		var s = 0;
		if (!a) { return 0; }
		for (var i = Math.max(0, a.length - last); i < a.length; i++) { s += a[i]; }
		return s;
	}

	/* ---------- snapshot ---------- */

	function loadSnapshot() {
		if (removed) { return Promise.resolve(); }
		return Rift.api.getJson('/api/datasources/' + encodeURIComponent(dsid) + '/comms').then(function (snap) {
			var seen = {};
			rev = snap.rev;
			lastT = snap.t;
			(snap.channels || []).forEach(function (c) {
				seen[c.id] = true;
				channels[c.id] = c;
			});
			Object.keys(channels).forEach(function (id) { if (!seen[id]) { delete channels[id]; } });
			render();
			$('cm-upd').textContent = 'UPD ' + ui.timeZ(new Date());
		}, function (err) {
			if (err && err.status === 404) { gone(); return; }
			ui.clear($('cm-list')).appendChild(ui.errorRow('Could not load the channels', err, loadSnapshot));
		});
	}

	function scheduleReload() {
		clearTimeout(reloadTimer);
		reloadTimer = setTimeout(loadSnapshot, 400);
	}

	/* ---------- channel list ---------- */

	function order() {
		return Object.keys(channels).map(function (id) { return channels[id]; }).sort(function (a, b) {
			if (a.family !== b.family) { return a.family === 'talk' ? -1 : 1; }
			if (a.family === 'talk') {
				if (a.kind === 'lobby' || b.kind === 'lobby') { return a.kind === 'lobby' ? -1 : 1; }
				return String(a.name).localeCompare(String(b.name));
			}
			var fa = a.frequency || Number.MAX_VALUE, fb = b.frequency || Number.MAX_VALUE;
			return fa !== fb ? fa - fb : String(a.name).localeCompare(String(b.name));
		});
	}

	function visible(c) {
		var q = $('cm-filter').value.trim().toLowerCase();
		if ($('cm-active').checked && sum(c.activity, SECONDS) === 0 && !listening[c.id]) { return false; }
		if (!q) { return true; }
		var hay = [c.name, c.description, c.kind, hz(c.frequency), c.encoding].concat((c.sources || []).map(function (s) {
			return [s.label, s.equipment, s.host ? s.host.label : '', s.hostname].join(' ');
		})).join(' ').toLowerCase();
		return hay.indexOf(q) >= 0;
	}

	function render() {
		var list = $('cm-list');
		var all = order();
		if (!all.length) {
			ui.clear(list).appendChild(ui.emptyBlock('No comms seen yet',
				'Talk channels, radio transmitters and radio signals show up here as the datasource receives them.'));
			rows = {};
			facts();
			renderChatTabs();
			return;
		}
		if (list.querySelector(':scope > .state')) { ui.clear(list); }
		var sections = { talk: section(list, 'talk', 'Talk'), radio: section(list, 'radio', 'Radio · RPR / DIS') };
		var keep = {};
		var counts = { talk: 0, radio: 0 };
		all.forEach(function (c) {
			keep[c.id] = true;
			var r = rows[c.id] || (rows[c.id] = makeRow(c));
			fillRow(r, c);
			var show = visible(c);
			r.root.hidden = !show;
			if (show) { counts[c.family === 'talk' ? 'talk' : 'radio']++; }
			sections[c.family === 'talk' ? 'talk' : 'radio'].body.appendChild(r.root);    // also puts it in order
		});
		Object.keys(rows).forEach(function (id) {
			if (!keep[id]) {
				if (rows[id].root.parentNode) { rows[id].root.parentNode.removeChild(rows[id].root); }
				delete rows[id];
				if (listening[id]) { delete listening[id]; audioChannels(); }
			}
		});
		['talk', 'radio'].forEach(function (k) {
			var has = all.some(function (c) { return (c.family === 'talk' ? 'talk' : 'radio') === k; });
			sections[k].root.hidden = !has;
			sections[k].count.textContent = counts[k] + (counts[k] === 1 ? ' channel' : ' channels');
		});
		facts();
		renderChatTabs();
	}

	function section(list, key, title) {
		var s = list.querySelector('[data-section="' + key + '"]');
		if (!s) {
			var count = el('span.cm-sec-count');
			var body = el('div.cm-sec-body');
			s = el('div.cm-section', { 'data-section': key }, [el('div.cm-sec-head', null, [el('span.cm-sec-title', { text: title }), count]), body]);
			list.appendChild(s);
			if (key === 'talk' && list.firstChild !== s) { list.insertBefore(s, list.firstChild); }
		}
		return { root: s, body: s.querySelector('.cm-sec-body'), count: s.querySelector('.cm-sec-count') };
	}

	function makeRow(c) {
		var r = {};
		r.cb = el('input', { type: 'checkbox', 'aria-label': 'Listen to ' + c.name });
		r.cb.addEventListener('change', function () { toggleListen(c.id, r.cb.checked); });
		r.vol = el('input.cm-chvol', { type: 'range', min: '0', max: '100', value: '100', 'aria-label': 'Volume of ' + c.name, title: 'Volume' });
		r.vol.addEventListener('input', function () { if (audio) { audio.setVolume(c.id, r.vol.value / 100); } });
		r.meter = el('span.cm-meter', null, [el('span.cm-meter-bar')]);
		r.listen = el('label.cm-listen-cb', { title: 'Listen' }, [r.cb, el('span', { text: 'Listen' })]);
		r.lamp = el('span.lamp.off', { 'aria-hidden': 'true' });
		r.name = el('span.cm-name');
		r.badges = el('span.cm-badges');
		r.canvas = el('canvas.cm-spark', { width: '240', height: '30', 'aria-label': 'Activity, last two minutes' });
		r.rate = el('span.cm-rate');
		r.last = el('span.cm-last');
		r.sources = el('div.cm-sources');
		r.chatBtn = el('button.btn.cm-chatbtn', { type: 'button', text: 'Transcript' });
		r.chatBtn.addEventListener('click', function () { openChat(c.id); });
		r.root = el('article.cm-row', { 'data-id': c.id }, [
			el('div.cm-row-main', null, [
				el('div.cm-row-title', null, [r.lamp, r.name, r.badges]),
				el('div.cm-row-graph', null, [r.canvas, el('div.cm-row-nums', null, [r.rate, r.last])]),
				el('div.cm-row-ctl', null, [r.listen, r.vol, r.meter, r.chatBtn])
			]),
			r.sources
		]);
		return r;
	}

	function badge(text, cls, title) {
		return el('span.cm-badge' + (cls ? '.' + cls : ''), { text: text, title: title || null });
	}

	function fillRow(r, c) {
		r.name.textContent = c.name;
		r.name.title = (c.description ? c.description + ' · ' : '') + c.id;
		var b = ui.clear(r.badges);
		b.appendChild(badge(c.family === 'talk' ? (KIND_LABEL[c.kind] || String(c.kind).toUpperCase()) : 'RF', 'k-' + (c.family === 'talk' ? c.kind : 'rf')));
		if (c.family === 'talk') { b.appendChild(badge('TALK', 'fam')); }
		if (c.frequency && c.family === 'talk') { b.appendChild(badge(hz(c.frequency), 'freq')); }
		if (c.family === 'talk' && c.present === false) { b.appendChild(badge('NO OBJECT', 'dim', 'No Talk channel object (seen in messages only, or removed)')); }
		var opusNo = c.codec === 'opus' && opusOk === false;
		if (c.encoding) {
			b.appendChild(badge(c.encoding, c.playable && !opusNo ? 'enc' : 'bad', opusNo ? OPUS_NO : c.codec === 'opus' ? 'Opus: decoded by this browser'
				: c.unplayable || null));
		}
		Object.keys(c.signals || {}).forEach(function (k) { b.appendChild(badge(k + ' ×' + c.signals[k], 'sig', 'Signals without audio')); });
		var canListen = canHear(c);
		r.listen.hidden = !canListen;
		r.vol.hidden = !canListen || !listening[c.id];
		r.meter.hidden = !canListen || !listening[c.id];
		r.cb.checked = !!listening[c.id];
		r.cb.disabled = (c.audio && !c.playable) || opusNo;
		r.listen.title = opusNo ? OPUS_NO : c.audio && !c.playable ? (c.unplayable || 'Not playable') : 'Listen';
		r.chatBtn.hidden = !c.chat;
		r.chatBtn.classList.toggle('on', chatOpen === c.id);
		r.root.classList.toggle('listening', !!listening[c.id]);
		drawSpark(r, c);
		fillSources(r, c);
	}

	/**
	 * Audio may be heard: Talk voice / radio / lobby; a radio frequency that carried audio, or
	 * nothing yet. A frequency with signals but never audio (data links) only shows its activity.
	 */
	function canHear(c) {
		if (c.family === 'talk') { return c.kind !== 'chat' || c.audio; }
		return c.audio || !(c.signals && Object.keys(c.signals).length);
	}

	function fillSources(r, c) {
		var box = ui.clear(r.sources);
		var list = c.sources || [];
		if (!list.length) {
			box.appendChild(el('span.cm-nosrc', { text: c.family === 'talk' ? 'No senders heard yet' : 'No transmitter object on this frequency' }));
			return;
		}
		list.forEach(function (s) {
			var chip = el('span.cm-src' + (s.talking ? '.talking' : ''), { 'data-src': String(s.id) });
			chip.appendChild(el('span.lamp' + (s.talking ? '' : '.off'), { 'aria-hidden': 'true' }));
			if (s.object) {
				chip.appendChild(el('a.cm-src-name', { href: dsLink(s.object.instanceid), target: 'rift-ds-' + dsid,
					title: (s.object.classname || '') + ' ' + s.object.instanceid + ' - show it on the datasource page', text: s.label || s.object.label }));
			} else {
				chip.appendChild(el('span.cm-src-name', { text: s.label, title: s.uuid ? 'Talk client ' + s.uuid : null }));
			}
			if (s.equipment) { chip.appendChild(el('span.cm-src-dim', { text: s.equipment })); }
			if (s.kind === 'transmitter' && s.status !== undefined) {
				chip.appendChild(el('span.cm-tx.s' + s.status, { text: STATUS_TEXT[s.status] || String(s.status), title: 'TransmitterOperationalStatus' }));
			}
			if (s.host) {
				chip.appendChild(el('span.cm-src-dim', { text: 'on' }));
				chip.appendChild(el('a.cm-host', { href: dsLink(s.host.instanceid), target: 'rift-ds-' + dsid,
					title: (s.host.classname || '') + ' ' + s.host.instanceid + ' - the host platform', text: s.host.label }));
				if (s.host.classname) { chip.appendChild(el('span.cm-src-dim', { text: s.host.classname })); }
			} else if (s.hostname) {
				chip.appendChild(el('span.cm-src-dim', { text: 'on ' + s.hostname, title: 'Host object not discovered' }));
			}
			if (s.lastactive) { chip.appendChild(el('span.cm-src-dim.cm-src-age', { 'data-iso': s.lastactive, text: ago(s.lastactive) })); }
			box.appendChild(chip);
		});
	}

	var colours = null;
	function themeColours() {
		var cs = getComputedStyle(document.documentElement);
		colours = { accent: cs.getPropertyValue('--accent').trim() || '#8fc45a', amber: cs.getPropertyValue('--amber').trim() || '#e3a83b',
			rule: cs.getPropertyValue('--rule-strong').trim() || '#2c3b31', faint: cs.getPropertyValue('--fg-faint').trim() || '#5b6c5b' };
	}

	function drawSpark(r, c) {
		var cv = r.canvas;
		var dpr = window.devicePixelRatio || 1;
		var w = cv.clientWidth || 240, h = cv.clientHeight || 30;
		if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
		var g = cv.getContext('2d');
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		g.clearRect(0, 0, w, h);
		if (!colours) { themeColours(); }
		var a = c.activity || [];
		var max = 4;
		a.forEach(function (v) { if (v > max) { max = v; } });
		var bw = w / SECONDS;
		g.fillStyle = colours.rule;
		g.fillRect(0, h - 1, w, 1);
		g.fillStyle = c.audio ? colours.accent : c.chat ? colours.amber : colours.faint;
		if (c.audio && !c.playable) { g.fillStyle = colours.faint; }
		for (var i = 0; i < a.length; i++) {
			if (!a[i]) { continue; }
			var bh = Math.max(2, Math.round((h - 2) * Math.sqrt(a[i] / max)));
			g.fillRect(i * bw, h - 1 - bh, Math.max(1, bw - 0.5), bh);
		}
		var last = a.length ? a[a.length - 1] : 0;
		r.rate.textContent = last + '/s';
		r.rate.title = 'Messages or frames in the last second · ' + c.frames + ' in all';
		r.last.textContent = c.lastactivity ? ago(c.lastactivity) + ' ago' : 'silent';
		r.last.setAttribute('data-iso', c.lastactivity || '');
	}

	/* ---------- live ---------- */

	function onTick(d) {
		if (!d) { return; }
		if (d.rev !== rev) { scheduleReload(); }
		var t = d.t;
		if (t <= lastT) { return; }
		var steps = Math.min(SECONDS, t - lastT);
		Object.keys(channels).forEach(function (id) {
			var c = channels[id];
			var a = c.activity || (c.activity = new Array(SECONDS).fill(0));
			for (var s = 0; s < steps; s++) { a.shift(); a.push(0); }
			var x = d.channels && d.channels[id];
			var talking = {};
			if (x) {
				a[a.length - 1] = x.n || 0;
				if (x.lastactivity) { c.lastactivity = x.lastactivity; }
				(x.talking || []).forEach(function (sid) { talking[sid] = true; });
			}
			(c.sources || []).forEach(function (s) { s.talking = !!talking[s.id]; });
			var r = rows[id];
			if (r) {
				drawSpark(r, c);
				var any = Object.keys(talking).length > 0;
				r.lamp.className = 'lamp' + (any ? '' : ' off');
				r.root.classList.toggle('talking', any);
				Array.prototype.forEach.call(r.sources.querySelectorAll('.cm-src'), function (chip) {
					var on = !!talking[chip.getAttribute('data-src')];
					chip.classList.toggle('talking', on);
					chip.querySelector('.lamp').className = 'lamp' + (on ? '' : ' off');
				});
			}
		});
		lastT = t;
		facts();
	}

	function onChat(d) {
		if (!d || !d.channel || !d.line) { return; }
		var c = channels[d.channel];
		if (c) { c.chatcount = (c.chatcount || 0) + 1; c.chat = true; }
		if (!c) { scheduleReload(); }
		if (d.channel === chatOpen && d.line.seq > chatSeq) {
			chatLines.push(d.line);
			chatSeq = d.line.seq;
			appendLine(d.line, true);
		}
		renderChatTabs();
	}

	function startStream() {
		setStream('connecting');
		Rift.api.subscribe(Rift.api.endpoints.events(['comms:' + dsid, 'ds:' + dsid]), {
			onOpen: function () { setStream('live'); loadSnapshot(); },
			onError: function () { setStream('lost'); },
			events: {
				'comms.tick': onTick,
				'comms.chat': onChat,
				'datasource.removed': gone,
				'datasource.state': function (d) { if (d) { setState(d.state, d.statemessage); } }
			}
		});
		// without the stream the page still follows, more slowly
		setInterval(function () { if (!streamLive && !removed) { loadSnapshot(); } }, 5000);
		setInterval(function () {
			Array.prototype.forEach.call(document.querySelectorAll('[data-iso]'), function (n) {
				var iso = n.getAttribute('data-iso');
				if (!iso) { return; }
				n.textContent = ago(iso) + (n.classList.contains('cm-last') ? ' ago' : '');
			});
		}, 1000);
	}

	/* ---------- chat ---------- */

	function chatChannels() {
		return order().filter(function (c) { return c.chat; });
	}

	function renderChatTabs() {
		var tabs = ui.clear($('cm-chat-tabs'));
		var list = chatChannels();
		// no chat channel: the channel list takes the whole width
		document.querySelector('.cm-chat').hidden = !list.length;
		document.querySelector('.cm-grid').classList.toggle('no-chat', !list.length);
		if (!list.length) {
			tabs.appendChild(el('span.cm-hint', { text: 'No chat channels yet.' }));
		}
		list.forEach(function (c) {
			var b = el('button.cm-tab' + (c.id === chatOpen ? '.on' : ''), { type: 'button', role: 'tab', 'aria-selected': c.id === chatOpen ? 'true' : 'false' },
				[el('span', { text: c.name }), el('span.cm-tab-n', { text: String(c.chatcount || 0) })]);
			b.addEventListener('click', function () { openChat(c.id); });
			tabs.appendChild(b);
		});
		if (!chatOpen && list.length) {
			// the busiest chat channel first (the lobby is listed first but is often quiet)
			var best = list.slice().sort(function (a, b) { return (b.chatcount || 0) - (a.chatcount || 0); })[0];
			openChat(best.id);
		}
		if (!list.length) {
			var t = ui.clear($('cm-transcript'));
			t.appendChild(el('div.cm-hint.cm-pad', { text: 'Talk chat channels and the lobby show their messages here.' }));
		}
	}

	function openChat(id) {
		var first = chatOpen !== id;
		chatOpen = id;
		Object.keys(rows).forEach(function (k) { rows[k].chatBtn.classList.toggle('on', k === id); });
		Array.prototype.forEach.call(document.querySelectorAll('.cm-tab'), function (b, i) {
			var c = chatChannels()[i];
			var on = c && c.id === id;
			b.classList.toggle('on', on);
			b.setAttribute('aria-selected', on ? 'true' : 'false');
		});
		if (!first) { return; }
		var box = ui.clear($('cm-transcript'));
		box.appendChild(ui.loadingRow('Loading the transcript'));
		Rift.api.getJson('/api/datasources/' + encodeURIComponent(dsid) + '/comms/chat?channel=' + encodeURIComponent(id) + '&limit=500').then(function (r) {
			if (chatOpen !== id) { return; }
			chatLines = r.lines || [];
			chatSeq = chatLines.length ? chatLines[chatLines.length - 1].seq : 0;
			ui.clear(box);
			$('cm-chat-count').textContent = (r.total || 0) + ' MESSAGES';
			if (!chatLines.length) { box.appendChild(el('div.cm-hint.cm-pad', { text: 'No messages yet.' })); }
			chatLines.forEach(function (l) { appendLine(l, false); });
			box.scrollTop = box.scrollHeight;
		}, function (err) {
			ui.clear(box).appendChild(ui.errorRow('Could not load the transcript', err, function () { chatOpen = null; openChat(id); }));
		});
	}

	var senderHue = {};
	function hue(name) {
		if (senderHue[name] === undefined) {
			var h = 0;
			for (var i = 0; i < name.length; i++) { h = (h * 31 + name.charCodeAt(i)) % 360; }
			senderHue[name] = h;
		}
		return senderHue[name];
	}

	function appendLine(l, live) {
		var box = $('cm-transcript');
		var hint = box.querySelector('.cm-hint');
		if (hint) { ui.clear(box); }
		var atEnd = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
		var who = el('span.cm-who', { text: l.sender || '?', title: l.senderid ? 'Talk client ' + l.senderid : null });
		who.style.setProperty('--h', hue(l.sender || '?'));
		box.appendChild(el('div.cm-line' + (l.kind === 'image' ? '.img' : '') + (live ? '.new' : ''), null, [
			el('span.cm-time', { text: ui.zulu(l.time, false), title: l.time }), who, el('span.cm-text', { text: l.text })
		]));
		while (box.childNodes.length > 500) { box.removeChild(box.firstChild); }
		if (atEnd || !live) { box.scrollTop = box.scrollHeight; }
		var c = channels[chatOpen];
		$('cm-chat-count').textContent = (c ? c.chatcount || 0 : chatLines.length) + ' MESSAGES';
	}

	/* ---------- listening ---------- */

	function toggleListen(id, on) {
		if (!audio) { return; }
		if (on) {
			audio.unlock();
			listening[id] = true;
		} else {
			delete listening[id];
		}
		if (rows[id] && channels[id]) { fillRow(rows[id], channels[id]); }
		audioChannels();
	}

	function audioChannels() {
		var ids = Object.keys(listening);
		$('cm-stop').disabled = !ids.length;
		if (audio) { audio.setChannels(ids); }
	}

	function setAudioStatus(s, detail) {
		var n = $('cm-audio');
		var n2 = Object.keys(listening).length;
		n.className = 'cm-audio ' + s;
		n.textContent = s === 'live' ? 'LIVE · ' + n2 + (n2 === 1 ? ' CHANNEL' : ' CHANNELS') : s === 'connecting' ? 'CONNECTING' : s === 'error' ? 'ERROR' : 'OFF';
		n.title = detail || '';
		$('cm-listen-hint').textContent = s === 'error' ? detail : 'Tick the channels to listen to. Your browser plays them mixed.';
	}

	function startAudio() {
		if (!Rift.CommsAudio.supported()) {
			$('cm-listen-hint').textContent = 'This browser cannot play the audio (Web Audio and streaming fetch are needed).';
			return;
		}
		audio = new Rift.CommsAudio({
			dsid: dsid,
			onStatus: setAudioStatus,
			onLevel: function (id, v) { levels[id] = Math.max(levels[id] || 0, Math.min(1, v * 3)); },
			onDecodeError: function (id, msg) {
				var c = channels[id];
				$('cm-listen-hint').textContent = 'Opus decoding failed' + (c ? ' on ' + c.name : '') + ': ' + msg;
			}
		});
		Rift.CommsAudio.opusSupported().then(function (ok) {
			opusOk = ok;
			Object.keys(rows).forEach(function (id) { if (channels[id]) { fillRow(rows[id], channels[id]); } });
		});
		$('cm-master').addEventListener('input', function () { audio.setMaster($('cm-master').value / 100); });
		$('cm-stop').addEventListener('click', function () {
			Object.keys(listening).forEach(function (id) { delete listening[id]; if (rows[id]) { fillRow(rows[id], channels[id]); } });
			audioChannels();
		});
		setInterval(function () {
			Object.keys(levels).forEach(function (id) {
				var r = rows[id];
				if (r) { r.meter.firstChild.style.width = Math.round(levels[id] * 100) + '%'; }
				levels[id] *= 0.6;
				if (levels[id] < 0.01) { levels[id] = 0; }
			});
		}, 100);
	}

	/** For diagnostics (and the page tests): audio frames played so far. */
	Rift.commsPage = {
		framesPlayed: function () { return audio ? audio.frames : 0; },
		opusErrors: function () { return audio ? audio.opusErrors : 0; },
		opusSupported: function () { return opusOk; }
	};

	/* ---------- start-up ---------- */

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		if (!dsid) {
			message('err', 'NO ID', 'Open this page from a datasource (comms.html?id=<datasource id>).');
			return;
		}
		$('cm-ds-link').href = 'datasource.html?id=' + encodeURIComponent(dsid);
		$('cm-filter').addEventListener('input', render);
		$('cm-active').addEventListener('change', render);
		new MutationObserver(function () { colours = null; Object.keys(rows).forEach(function (id) { drawSpark(rows[id], channels[id]); }); })
			.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
		window.addEventListener('resize', function () { Object.keys(rows).forEach(function (id) { drawSpark(rows[id], channels[id]); }); });
		startAudio();
		loadHeader();
		loadSnapshot();
		startStream();
	});
})(window, document);
