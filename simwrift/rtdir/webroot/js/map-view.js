/* ============================================================
   SimWeaver Rift - map view (NEW).
   The objects of a datasource's object list filter that have a
   position, on a zoomable map: circles coloured by force, sized by
   zoom, labelled with marking, entity id and class. Positions are
   the server's dead-reckoned Spatial (polled once a second) and are
   moved on between polls by their velocity.

   - Objects carried by another (radios, mounted entities) are not
     drawn on their own: their host shows a "+N" badge; click it to
     fan them out.
   - Click an entity for its card: Center, Follow, Details.
   - Search box: go to an entity by marking, entity id or class.

   No libraries: one canvas, Web Mercator tiles from /api/maps.

   var mv = new Rift.MapView(hostElement, {
       dsid, filter: {class:[], subclasses, q, related},
       onOpenObject: function ({instanceid, label, classname}) {...},   // "Details"
       storageKey: 'rift-map:' + dsid,
       map: 'HYP_HR_SR_W_DR',                 // optional: this background map (else the user's, else the server's)
       onMapChange: function (id) {...}       // the user picked another background map
   });
   The background map the user picks is remembered in this browser for every datasource
   (localStorage 'rift-map-background'); the server's default is map.file / map.default.
   mv.start(); mv.setFilter(f); mv.goTo(id); mv.follow(id); mv.stop();
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var TILE = 256;
	var MIN_ZOOM = 1, MAX_ZOOM = 18;
	var EARTH = 6378137;
	var POLL_MS = 1000, POLL_HIDDEN_MS = 5000;
	var FRAME_MS = 50;                          // at most 20 frames per second
	var MAX_EXTRAPOLATE_S = 3;
	var BACKGROUND_KEY = 'rift-map-background';  // the user's background map, all datasources
	var OVERLAY_KEY = 'rift-map-overlays';       // flashes, lasers on/off, beams off/length (all datasources)
	var SHAPES_KEY = 'rift-map-shape-overlays';  // shapefile overlays on/off, by id (all datasources)
	var OV_LABEL_FONT = '600 11px "IBM Plex Sans", "Segoe UI", Arial, sans-serif';

	/* Fire and detonation flashes: a short blink where it happened, then nothing. */
	var FLASH = {
		fire:       { ms: 900,  c: '#ffd166', ring: '#ff8c1a', label: 'Fire' },
		detonation: { ms: 1400, c: '#fff3b0', ring: '#ff3b30', label: 'Detonation' }
	};
	var FLASH_MAX_AGE_S = 4;                      // older events (late poll, hidden tab) are not shown

	/* Radar and jammer beams. The RPR FOM gives no range: the length is the user's choice. */
	var BEAM = {
		radar:  { c: '53, 211, 154', label: 'Radar beam' },
		jammer: { c: '212, 91, 224', label: 'Jammer beam' }
	};
	var BEAM_LENGTHS = [0, 25, 50, 100, 200, 400];  // km; 0 = a fixed length on the screen
	var BEAM_AUTO_PX = 120;

	/* Laser designators: a line from the designating object to the spot, which pulses. */
	var LASER = { c: '255, 45, 85', label: 'Laser' };
	var LASER_PULSE_MS = 1200;

	var FORCE = {
		friendly: { c: '#2f80ed', l: 'Friendly' },
		opposing: { c: '#e5484d', l: 'Opposing' },
		neutral: { c: '#2fa36b', l: 'Neutral' },
		other: { c: '#f0b429', l: 'Other' },
		none: { c: '#9aa3ad', l: 'No force' }
	};

	/* ---------- Web Mercator, in pixels of zoom z (fractional zoom allowed) ---------- */

	function world(z) { return TILE * Math.pow(2, z); }
	function lonToX(lon, z) { return (lon + 180) / 360 * world(z); }
	function latToY(lat, z) {
		var s = Math.sin(lat * Math.PI / 180);
		s = Math.max(-0.9999, Math.min(0.9999, s));
		return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * world(z);
	}
	function xToLon(x, z) { return x / world(z) * 360 - 180; }
	function yToLat(y, z) {
		var n = Math.PI - 2 * Math.PI * y / world(z);
		return 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
	}
	function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

	function fmtLat(v) { return Math.abs(v).toFixed(4) + '°' + (v >= 0 ? 'N' : 'S'); }
	function fmtLon(v) { return Math.abs(v).toFixed(4) + '°' + (v >= 0 ? 'E' : 'W'); }
	function dms(v, pos, neg) {
		var a = Math.abs(v), d = Math.floor(a), m = Math.floor((a - d) * 60), s = ((a - d) * 60 - m) * 60;
		return d + '°' + (m < 10 ? '0' : '') + m + '′' + (s < 10 ? '0' : '') + s.toFixed(1) + '″' + (v >= 0 ? pos : neg);
	}

	/* ---------- the map list, once per page ---------- */

	var mapsPromise = null;
	/** @returns {Promise<{enabled, maps, default}>} (never rejects: offline -> enabled, no maps) */
	function maps() {
		if (!mapsPromise) {
			mapsPromise = Rift.api.fetchMaps().then(function (d) {
				return { enabled: !!(d && d.enabled), maps: (d && d.maps) || [], 'default': d && d['default'], problems: (d && d.problems) || [],
					overlays: (d && d.overlays) || [] };
			}, function () {
				mapsPromise = null;
				return { enabled: true, maps: [], 'default': null, problems: ['The map list could not be loaded'] };
			});
		}
		return mapsPromise;
	}

	/* ============================================================
	   MapView
	   ============================================================ */

	function MapView(host, opts) {
		this.host = host;
		this.opts = opts || {};
		this.dsid = this.opts.dsid;
		this.filter = this.opts.filter || {};
		this.storageKey = this.opts.storageKey || ('rift-map:' + this.dsid);
		this.view = { lat: 30, lon: 10, zoom: 2 };
		this.labels = 'auto';
		this.haveView = false;
		this.objects = [];
		this.byId = {};
		this.childIndex = {};                  // child id -> {child, parent}
		this.selected = null;                  // {id, child:bool}
		this.followId = null;
		this.expanded = {};
		this.tiles = new Map();
		this.loading = 0;
		this.overlayList = [];                 // shapefile overlays of the server (GET /api/maps)
		this.ovOn = {};                        // id -> shown (the user's choice; else the overlay's "visible")
		this.ovTiles = new Map();
		this.ovLoading = 0;
		this.ovLabels = {};                    // id -> {zoom, bbox, list, pending}
		this.map = null;
		this.mapList = [];
		this.serverOffset = 0;                 // server now - client now (ms)
		this.lastData = 0;
		this.running = false;
		this.dirty = true;
		this.hits = [];
		this.info = { matched: 0, shown: 0, children: 0, unpositioned: 0 };
		this.overlays = { flashes: true, beams: false, beamKm: 0, lasers: true };
		this.eventSeq = 0;
		this.flashes = [];                     // {e, start}
		this.seenEvents = {};
		this.beams = [];
		this.lasers = [];
		this.restore();
		this.build();
		MapView.last = this;                  // for tests and the browser console
	}

	/** Screen point of an object (or a carried one's host) now, or null. For tests. */
	MapView.prototype.screenOf = function (id) {
		var o = this.byId[id] || (this.childIndex[id] || {}).parent;
		if (!o) { return null; }
		var z = this.view.zoom, p = this.posOf(o);
		return this.screen(p.lat, p.lon, z, lonToX(this.view.lon, z), latToY(this.view.lat, z));
	};

	MapView.prototype.restore = function () {
		try {
			var s = JSON.parse(window.localStorage.getItem(this.storageKey) || 'null');
			if (s && typeof s.lat === 'number' && typeof s.lon === 'number' && typeof s.zoom === 'number') {
				this.view = { lat: s.lat, lon: s.lon, zoom: clamp(s.zoom, MIN_ZOOM, MAX_ZOOM) };
				this.haveView = true;
			}
			if (s && (s.labels === 'auto' || s.labels === 'all' || s.labels === 'off')) { this.labels = s.labels; }
			// (the first map version stored the map shown per datasource, even when it was only the
			// default of that time: not used, it would hide a new server default)
			var b = window.localStorage.getItem(BACKGROUND_KEY);
			if (b) { this.wantMap = b; }
			var sh = JSON.parse(window.localStorage.getItem(SHAPES_KEY) || 'null');
			if (sh && typeof sh === 'object') { this.ovOn = sh; }
			var ov = JSON.parse(window.localStorage.getItem(OVERLAY_KEY) || 'null');
			if (ov) {
				this.overlays.flashes = ov.flashes !== false;
				this.overlays.beams = ov.beams === true;
				this.overlays.beamKm = BEAM_LENGTHS.indexOf(ov.beamKm) >= 0 ? ov.beamKm : 0;
				this.overlays.lasers = ov.lasers !== false;
			}
		} catch (e) { /* storage blocked */ }
		if (this.opts.map) { this.wantMap = String(this.opts.map); this.wantFromCaller = true; }
	};

	MapView.prototype.save = function () {
		var self = this;
		clearTimeout(this.saveTimer);
		this.saveTimer = setTimeout(function () {
			try {
				window.localStorage.setItem(self.storageKey, JSON.stringify({ lat: self.view.lat, lon: self.view.lon,
					zoom: self.view.zoom, labels: self.labels }));
			} catch (e) { /* storage blocked */ }
		}, 400);
	};

	/* ---------- DOM ---------- */

	MapView.prototype.build = function () {
		var self = this;
		ui.clear(this.host);
		this.canvas = el('canvas.mv-canvas', { tabindex: '0', 'aria-label': 'Map. Drag to pan, wheel or + and - to zoom, click an entity.' });
		this.zoomIn = el('button.mv-btn', { type: 'button', title: 'Zoom in (+)', 'aria-label': 'Zoom in' }, '+');
		this.zoomOut = el('button.mv-btn', { type: 'button', title: 'Zoom out (-)', 'aria-label': 'Zoom out' }, '−');
		this.fitBtn = el('button.mv-btn', { type: 'button', title: 'Show all entities', 'aria-label': 'Show all entities' }, '⤢');
		this.labelBtn = el('button.mv-btn.mv-wide', { type: 'button', title: 'Labels: automatic / all / off' });
		this.flashBtn = el('button.mv-btn.mv-wide.mv-toggle', { type: 'button', title: 'A short flash where weapons fire and detonations happen: on / off' }, '✸ FLASHES');
		this.laserBtn = el('button.mv-btn.mv-wide.mv-toggle', { type: 'button', title: 'Laser designators: a line from the designator to the laser spot: on / off' }, '⌁ LASERS');
		this.beamSel = el('select.mv-mapsel.mv-beamsel', { 'aria-label': 'Radar and jammer beams', title: 'Radar and jammer beams: off, or the length to draw them (the FOM has no range)' },
			[el('option', { value: 'off', text: '◔ Beams: off' })].concat(BEAM_LENGTHS.map(function (km) {
				return el('option', { value: String(km), text: '◔ Beams: ' + (km ? km + ' km' : 'on (fixed length)') });
			})));
		this.mapSel = el('select.mv-mapsel', { 'aria-label': 'Background map', title: 'Background map (remembered in this browser)', hidden: true });
		this.ovBtn = el('button.mv-btn.mv-wide.mv-toggle', { type: 'button', hidden: true, 'aria-expanded': 'false',
			title: 'Map overlays (shapefiles of the server): on / off' }, '▦ OVERLAYS');
		this.ovPanel = el('div.mv-ovpanel', { hidden: true, role: 'group', 'aria-label': 'Map overlays' });
		this.search = el('input.mv-search', { type: 'search', placeholder: 'Go to: marking, id, class', 'aria-label': 'Go to an entity',
			autocomplete: 'off', spellcheck: 'false' });
		this.results = el('ul.mv-results', { hidden: true, role: 'listbox' });
		this.card = el('div.mv-card', { hidden: true, role: 'region', 'aria-label': 'Selected entity' });
		this.countNode = el('span.mv-count');
		this.liveNode = el('span.mv-live');
		this.cursorNode = el('span.mv-cursor');
		this.scaleNode = el('span.mv-scale', null, [el('span.mv-scale-bar'), el('span.mv-scale-text')]);
		this.attrNode = el('span.mv-attr');
		this.msg = el('div.mv-msg', { hidden: true });
		this.followNode = el('div.mv-following', { hidden: true });
		ui.append(this.host, el('div.mv', null, [
			this.canvas,
			el('div.mv-tools', null, [this.zoomIn, this.zoomOut, this.fitBtn, this.labelBtn, this.flashBtn, this.laserBtn, this.beamSel, this.mapSel, this.ovBtn, this.ovPanel]),
			el('div.mv-find', null, [this.search, this.results]),
			this.followNode,
			this.card,
			this.msg,
			this.legend = el('div.mv-legend', null, ['friendly', 'opposing', 'neutral', 'other'].map(function (f) {
				return el('span.mv-leg', null, [el('i', { style: 'background:' + FORCE[f].c }), FORCE[f].l]);
			}).concat([
				this.legFire = el('span.mv-leg', null, [el('i.mv-leg-flash', { style: 'background:' + FLASH.fire.c + ';border-color:' + FLASH.fire.ring }), 'Fire']),
				this.legDet = el('span.mv-leg', null, [el('i.mv-leg-flash', { style: 'background:' + FLASH.detonation.c + ';border-color:' + FLASH.detonation.ring }), 'Detonation']),
				this.legRadar = el('span.mv-leg', null, [el('i.mv-leg-beam', { style: 'background:rgba(' + BEAM.radar.c + ',0.45);border-color:rgb(' + BEAM.radar.c + ')' }), 'Radar']),
				this.legJam = el('span.mv-leg', null, [el('i.mv-leg-beam', { style: 'background:rgba(' + BEAM.jammer.c + ',0.45);border-color:rgb(' + BEAM.jammer.c + ')' }), 'Jammer']),
				this.legLaser = el('span.mv-leg', null, [el('i.mv-leg-laser', { style: 'background:rgb(' + LASER.c + ');box-shadow:0 0 4px rgb(' + LASER.c + ')' }), 'Laser'])
			])),
			el('div.mv-status', null, [this.countNode, this.liveNode, el('span.mv-gap'), this.cursorNode, this.scaleNode, this.attrNode])
		]));
		this.ctx = this.canvas.getContext('2d');
		this.updateLabelBtn();
		this.updateOverlayControls();
		this.flashBtn.addEventListener('click', function () {
			self.overlays.flashes = !self.overlays.flashes;
			if (!self.overlays.flashes) { self.flashes = []; }
			self.saveOverlays();
			self.dirty = true;
		});
		this.laserBtn.addEventListener('click', function () {
			self.overlays.lasers = !self.overlays.lasers;
			if (!self.overlays.lasers) { self.lasers = []; }
			self.saveOverlays();
			self.dirty = true;
			if (self.overlays.lasers) { self.poll(); }
		});
		this.beamSel.addEventListener('change', function () {
			var v = self.beamSel.value;
			self.overlays.beams = v !== 'off';
			if (self.overlays.beams) { self.overlays.beamKm = parseInt(v, 10) || 0; }
			if (!self.overlays.beams) { self.beams = []; }
			self.saveOverlays();
			self.dirty = true;
			if (self.overlays.beams) { self.poll(); }
		});

		this.ovBtn.addEventListener('click', function () {
			var open = self.ovPanel.hidden;
			self.ovPanel.hidden = !open;
			self.ovBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
			self.ovBtn.classList.toggle('on', open || self.anyOverlayOn());
		});

		this.zoomIn.addEventListener('click', function () { self.zoomBy(1); });
		this.zoomOut.addEventListener('click', function () { self.zoomBy(-1); });
		this.fitBtn.addEventListener('click', function () { self.fitAll(true); });
		this.labelBtn.addEventListener('click', function () {
			self.labels = self.labels === 'auto' ? 'all' : self.labels === 'all' ? 'off' : 'auto';
			self.updateLabelBtn();
			self.save();
			self.dirty = true;
		});
		this.mapSel.addEventListener('change', function () {
			var v = self.mapSel.value;                     // '' = follow the server's default
			var m = self.mapList.filter(function (x) { return x.id === (v || self.defaultMap); })[0] || self.mapList[0];
			self.followDefault = !v;
			self.useMap(m);
			try {
				if (v) { window.localStorage.setItem(BACKGROUND_KEY, v); }
				else { window.localStorage.removeItem(BACKGROUND_KEY); }
			} catch (e) { /* storage blocked */ }
			if (self.opts.onMapChange) { self.opts.onMapChange(v || null, !v); }
		});
		this.wireSearch();
		this.wirePointer();
		this.wireKeys();
		if (window.ResizeObserver) {
			this.ro = new ResizeObserver(function () { self.resize(); });
			this.ro.observe(this.host);
		}
		window.addEventListener('resize', function () { self.resize(); });
	};

	MapView.prototype.updateOverlayControls = function () {
		var o = this.overlays;
		this.flashBtn.classList.toggle('on', o.flashes);
		this.flashBtn.setAttribute('aria-pressed', o.flashes ? 'true' : 'false');
		this.laserBtn.classList.toggle('on', o.lasers);
		this.laserBtn.setAttribute('aria-pressed', o.lasers ? 'true' : 'false');
		this.beamSel.value = o.beams ? String(o.beamKm) : 'off';
		this.beamSel.classList.toggle('on', o.beams);
		this.legFire.hidden = this.legDet.hidden = !o.flashes;
		this.legRadar.hidden = this.legJam.hidden = !o.beams;
		this.legLaser.hidden = !o.lasers;
	};

	MapView.prototype.saveOverlays = function () {
		this.updateOverlayControls();
		try { window.localStorage.setItem(OVERLAY_KEY, JSON.stringify(this.overlays)); } catch (e) { /* storage blocked */ }
	};

	MapView.prototype.updateLabelBtn = function () {
		this.labelBtn.textContent = 'LBL ' + (this.labels === 'auto' ? 'AUTO' : this.labels === 'all' ? 'ALL' : 'OFF');
	};

	MapView.prototype.resize = function () {
		var r = this.canvas.getBoundingClientRect();
		var dpr = window.devicePixelRatio || 1;
		var w = Math.max(1, Math.round(r.width * dpr)), h = Math.max(1, Math.round(r.height * dpr));
		if (this.canvas.width !== w || this.canvas.height !== h) {
			this.canvas.width = w;
			this.canvas.height = h;
		}
		this.dpr = dpr;
		this.W = r.width;
		this.H = r.height;
		this.dirty = true;
	};

	/* ---------- start / stop ---------- */

	MapView.prototype.start = function () {
		var self = this;
		if (this.running) { return; }
		this.running = true;
		this.resize();
		maps().then(function (m) {
			if (!self.running) { return; }
			if (!m.enabled) {
				self.showMsg('The map is turned off on this server (map.enabled=false).');
				self.disabled = true;
				return;
			}
			self.mapList = m.maps;
			self.defaultMap = m['default'];
			ui.clear(self.mapSel);
			var def = m.maps.filter(function (x) { return x.id === m['default']; })[0] || m.maps[0] || null;
			if (def) {
				self.mapSel.appendChild(el('option', { value: '', text: 'Server default: ' + def.name,
					title: 'Follows the server setting (map.file): changes when the server\'s default changes' }));
			}
			m.maps.forEach(function (x) {
				self.mapSel.appendChild(el('option', { value: x.id, text: x.name, title: x.file + ' · ' + x.width + ' × ' + x.height + ' · kept in this browser' }));
			});
			self.mapSel.hidden = m.maps.length < 2;
			var want = m.maps.filter(function (x) { return x.id === self.wantMap; })[0];
			if (self.wantMap && !want) {
				if (self.wantFromCaller) {
					ui.toast('The background map "' + self.wantMap + '" is not on this server: showing the default.', 'info', 5000);
				} else {
					try { window.localStorage.removeItem(BACKGROUND_KEY); } catch (e) { /* storage blocked */ }   // gone from the server
				}
			}
			self.followDefault = !want;
			var pick = want || def;
			self.useMap(pick);
			if (!pick) {
				self.attrNode.textContent = 'no background map on the server';
				self.attrNode.title = (m.problems || []).join('\n');
			}
			self.setOverlays(m.overlays);
			self.poll();
		});
		var loop = function (t) {
			if (!self.running) { return; }
			self.raf = window.requestAnimationFrame(loop);
			if (t - (self.lastFrame || 0) < FRAME_MS) { return; }
			if (!self.dirty && !self.animating()) { return; }
			self.lastFrame = t;
			self.draw();
		};
		this.raf = window.requestAnimationFrame(loop);
	};

	MapView.prototype.stop = function () {
		this.running = false;
		clearTimeout(this.pollTimer);
		if (this.raf) { window.cancelAnimationFrame(this.raf); }
	};

	/* ---------- shapefile overlays ---------- */

	/** The server's overlays; asks again while some are still loading (big files). */
	MapView.prototype.setOverlays = function (list) {
		var self = this;
		this.overlayList = list || [];
		this.ovBtn.hidden = !this.overlayList.length;
		this.renderOverlayPanel();
		this.dirty = true;
		clearTimeout(this.ovRefresh);
		if (this.overlayList.some(function (o) { return o.state === 'loading'; })) {
			this.ovRefresh = setTimeout(function () {
				if (!self.running) { return; }
				Rift.api.fetchMaps().then(function (d) { if (self.running) { self.setOverlays((d && d.overlays) || []); } }, function () {
					self.ovRefresh = setTimeout(function () { self.setOverlays(self.overlayList); }, 5000);
				});
			}, 2500);
		}
	};

	MapView.prototype.overlayShown = function (o) {
		return Object.prototype.hasOwnProperty.call(this.ovOn, o.id) ? !!this.ovOn[o.id] : o.visible !== false;
	};

	MapView.prototype.anyOverlayOn = function () {
		var self = this;
		return this.overlayList.some(function (o) { return o.state === 'ready' && self.overlayShown(o); });
	};

	MapView.prototype.renderOverlayPanel = function () {
		var self = this;
		ui.clear(this.ovPanel);
		this.overlayList.forEach(function (o) {
			var cb = el('input', { type: 'checkbox' });
			cb.checked = self.overlayShown(o);
			cb.disabled = o.state !== 'ready';
			cb.addEventListener('change', function () {
				self.ovOn[o.id] = cb.checked;
				try { window.localStorage.setItem(SHAPES_KEY, JSON.stringify(self.ovOn)); } catch (e) { /* storage blocked */ }
				self.dirty = true;
			});
			var meta = o.state === 'ready' ? (o.features + ' ' + (o.kind || '') + (o.labelcount ? ' · labels from zoom ' + o.labelminzoom : ''))
				: o.state === 'loading' ? 'loading…' : 'could not be read (see the server log)';
			var tip = o.file + (o.crs ? ' · ' + o.crs : '') + (o.warnings ? '\n' + o.warnings.join('\n') : '');
			self.ovPanel.appendChild(el('label.mv-ov', { title: tip }, [
				cb,
				el('i.mv-ov-sw', { style: 'border-color:' + (o.color || '#888') + ';background:' + (o.fill || 'transparent') }),
				el('span.mv-ov-name', { text: o.name }),
				el('span.mv-ov-meta', { text: meta })
			]));
		});
		this.ovBtn.classList.toggle('on', !this.ovPanel.hidden || this.anyOverlayOn());
	};

	/* Overlay tiles: transparent PNGs over the background, at their own zoom (up to 18). */
	MapView.prototype.drawOverlayTiles = function (c, z, cx, cy) {
		var self = this, W = this.W, H = this.H;
		var tz = clamp(Math.floor(z + 0.15), 0, 18), scale = Math.pow(2, z - tz), n = 1 << tz;
		var ox = cx / scale, oy = cy / scale;
		var x0 = Math.floor((ox - W / 2 / scale) / TILE), x1 = Math.floor((ox + W / 2 / scale) / TILE);
		var y0 = Math.max(0, Math.floor((oy - H / 2 / scale) / TILE)), y1 = Math.min(n - 1, Math.floor((oy + H / 2 / scale) / TILE));
		var ts = TILE * scale;
		this.overlayList.forEach(function (o) {
			if (o.state !== 'ready' || !o.tiles || !self.overlayShown(o) || tz < o.minzoom || tz > o.maxzoom) { return; }
			for (var ty = y0; ty <= y1; ty++) {
				for (var tx = x0; tx <= x1; tx++) {
					var wx = ((tx % n) + n) % n;
					var sx = (tx * TILE - ox) * scale + W / 2, sy = (ty * TILE - oy) * scale + H / 2;
					var img = self.ovTile(o, tz, wx, ty, true);
					if (img) {
						if (img.naturalWidth > 1) { c.drawImage(img, sx, sy, ts + 0.5, ts + 0.5); }
						continue;
					}
					for (var up = 1; up <= 4 && tz - up >= o.minzoom; up++) {   // a coarser tile while it loads
						var f = 1 << up, p = self.ovTile(o, tz - up, Math.floor(wx / f), Math.floor(ty / f), false);
						if (p) {
							if (p.naturalWidth > 1) {
								var part = TILE / f;
								c.drawImage(p, (wx % f) * part, (ty % f) * part, part, part, sx, sy, ts + 0.5, ts + 0.5);
							}
							break;
						}
					}
				}
			}
		});
	};

	MapView.prototype.ovTile = function (o, z, x, y, load) {
		var key = o.id + '/' + o.version + '/' + z + '/' + x + '/' + y;
		var t = this.ovTiles.get(key);
		if (t) {
			this.ovTiles.delete(key);
			this.ovTiles.set(key, t);
			return t.ok ? t.img : null;
		}
		if (!load || this.ovLoading >= 8) { if (load) { this.dirty = true; } return null; }
		var self = this, img = new Image();
		t = { img: img, ok: false };
		this.ovTiles.set(key, t);
		this.ovLoading++;
		img.onload = function () { t.ok = true; self.ovLoading--; self.dirty = true; };
		img.onerror = function () { self.ovLoading--; setTimeout(function () { self.ovTiles.delete(key); }, 5000); };
		img.src = Rift.api.endpoints.overlayTile(o, z, x, y);
		if (this.ovTiles.size > 900) {
			var it = this.ovTiles.keys();
			for (var i = 0; i < 150; i++) { this.ovTiles.delete(it.next().value); }
		}
		return null;
	};

	/** Asks for the labels of the view (and some around it), per overlay, when the view leaves what was fetched. */
	MapView.prototype.wantOverlayLabels = function (z, cx, cy) {
		var self = this, zi = clamp(Math.floor(z + 0.15), 0, 18);
		var w = xToLon(cx - this.W / 2, z), e = xToLon(cx + this.W / 2, z);
		var n = yToLat(cy - this.H / 2, z), s = yToLat(cy + this.H / 2, z);
		if (this.W / world(z) >= 1 || w < -180 || e > 180) { w = Math.max(-180, w); e = Math.min(180, e); }
		this.overlayList.forEach(function (o) {
			var L = self.ovLabels[o.id] || (self.ovLabels[o.id] = { list: [], zoom: -1 });
			if (o.state !== 'ready' || !o.labels || !self.overlayShown(o) || zi < o.labelminzoom) {
				L.list = [];
				L.zoom = -1;
				return;
			}
			var b = L.bbox;
			if (L.zoom === zi && b && w >= b[0] && e <= b[2] && s >= b[1] && n <= b[3]) { return; }
			if (L.pending) { return; }
			var dx = (e - w) * 0.5, dy = (n - s) * 0.5;
			var bbox = [Math.max(-180, w - dx), Math.max(-85.06, s - dy), Math.min(180, e + dx), Math.min(85.06, n + dy)];
			L.pending = true;
			Rift.api.fetchOverlayLabels(o, bbox, zi).then(function (d) {
				L.pending = false;
				L.list = (d && d.labels) || [];
				L.zoom = zi;
				L.bbox = bbox;
				self.dirty = true;
			}, function () {
				L.pending = false;
				L.zoom = -1;
				L.bbox = null;
			});
		});
	};

	/* Labels: a white halo, in the order the server sends them (rank first); a label that would
	   overlap one already drawn is left out. A point's label goes to the right of its dot. */
	MapView.prototype.drawOverlayLabels = function (c, z, cx, cy) {
		var self = this, W = this.W, H = this.H, boxes = [];
		c.save();
		c.font = OV_LABEL_FONT;
		c.textBaseline = 'middle';
		c.lineJoin = 'round';
		this.overlayList.forEach(function (o) {
			var L = self.ovLabels[o.id];
			if (!L || !L.list.length || !self.overlayShown(o)) { return; }
			for (var i = 0; i < L.list.length; i++) {
				var l = L.list[i];
				var p = self.screen(l.lat, l.lon, z, cx, cy);
				if (p.x < -150 || p.x > W + 150 || p.y < -20 || p.y > H + 20) { continue; }
				var tw = c.measureText(l.t).width;
				var x = l.k === 'point' ? p.x + 6 : p.x - tw / 2;
				var box = [x - 3, p.y - 8, x + tw + 3, p.y + 8];
				var hit = false;
				for (var j = 0; j < boxes.length && !hit; j++) {
					var q = boxes[j];
					hit = box[0] < q[2] && box[2] > q[0] && box[1] < q[3] && box[3] > q[1];
				}
				if (hit) { continue; }
				boxes.push(box);
				c.lineWidth = 3;
				c.strokeStyle = 'rgba(255,255,255,0.85)';
				c.strokeText(l.t, x, p.y);
				c.fillStyle = o.labelcolor || '#1b2631';
				c.fillText(l.t, x, p.y);
			}
		});
		c.restore();
	};

	MapView.prototype.useMap = function (m) {
		this.map = m || null;
		if (m) { this.mapSel.value = this.followDefault ? '' : m.id; }
		this.tiles = new Map();
		this.attrNode.textContent = m && m.attribution ? '© ' + m.attribution : '';
		this.dirty = true;
	};

	/** Id of the background map shown, or null. */
	MapView.prototype.currentMap = function () {
		return this.map ? this.map.id : null;
	};

	/** True when the view follows the server's default map (no map picked by the user or the URL). */
	MapView.prototype.isDefaultMap = function () {
		return !this.map || !!this.followDefault;
	};

	MapView.prototype.setFilter = function (f) {
		this.filter = f || {};
		this.filterChanged = Date.now();
		this.poll(true);
	};

	MapView.prototype.showMsg = function (text) {
		this.msg.textContent = text || '';
		this.msg.hidden = !text;
	};

	/* ---------- data ---------- */

	MapView.prototype.poll = function (now) {
		var self = this;
		clearTimeout(this.pollTimer);
		if (!this.running || this.disabled) { return; }
		var seq = (this.pollSeq = (this.pollSeq || 0) + 1);
		var filter = this.opts.getFilter ? this.opts.getFilter() : this.filter;
		var extra = { events: this.overlays.flashes ? this.eventSeq : null, beams: this.overlays.beams, lasers: this.overlays.lasers };
		Rift.api.fetchMapObjects(this.dsid, filter, extra).then(function (d) {
			if (seq !== self.pollSeq) { return; }
			self.accept(d);
		}, function (err) {
			if (err && err.status === 404 && err.code === 'MAP_DISABLED') {
				self.disabled = true;
				self.showMsg('The map is turned off on this server (map.enabled=false).');
				return;
			}
			self.liveNode.textContent = 'NO DATA';
			self.liveNode.className = 'mv-live lost';
		}).then(function () {
			if (!self.running || self.disabled) { return; }
			self.pollTimer = setTimeout(function () { self.poll(); }, document.hidden ? POLL_HIDDEN_MS : POLL_MS);
		});
	};

	MapView.prototype.accept = function (d) {
		var t = Date.now();
		this.serverOffset = (d.now || t) - t;
		this.lastData = t;
		this.objects = d.objects || [];
		this.byId = {};
		this.childIndex = {};
		var self = this;
		this.objects.forEach(function (o) {
			o.t0 = d.now || t;
			self.byId[o.id] = o;
			(o.children || []).forEach(function (c) { self.childIndex[c.id] = { child: c, parent: o }; });
		});
		this.info = { matched: d.matched || 0, shown: d.shown || 0, children: d.children || 0, unpositioned: d.unpositioned || 0 };
		this.acceptOverlays(d);
		var parts = [this.info.shown + ' on map'];
		if (this.info.children) { parts.push(this.info.children + ' carried'); }
		if (this.info.unpositioned) { parts.push(this.info.unpositioned + ' without position'); }
		if (d.truncated) { parts.push('first ' + this.objects.length + ' only'); }
		this.countNode.textContent = parts.join(' · ');
		this.liveNode.textContent = 'LIVE';
		this.liveNode.className = 'mv-live ok';
		if (!this.haveView && this.objects.length) {
			this.fitAll(false);
			this.haveView = true;
		}
		if (!this.objects.length) {
			this.showMsg(this.info.unpositioned ? 'None of the ' + this.info.unpositioned + ' objects of this filter has a position.'
				: 'No objects with a position (yet).');
		} else {
			this.showMsg('');
		}
		if (this.selected && !this.byId[this.selected.id] && !this.childIndex[this.selected.id]) {
			this.select(null);
		} else if (this.selected) {
			this.renderCard();
		}
		if (this.pendingGoTo) {
			var id = this.pendingGoTo;
			this.pendingGoTo = null;
			this.goTo(id, { follow: this.pendingFollow });
		}
		this.updateFollowNode();
		this.dirty = true;
		if (this.opts.onData) { this.opts.onData(this.objects, this.info); }
	};

	/** Fire / detonation events (flashes), beams and lasers of a map answer. */
	MapView.prototype.acceptOverlays = function (d) {
		var self = this, now = performance.now();
		if (typeof d.eventseq === 'number') { this.eventSeq = d.eventseq; }
		if (this.overlays.flashes && d.events) {
			d.events.forEach(function (e) {
				if (self.seenEvents[e.id] || (e.age || 0) > FLASH_MAX_AGE_S) { return; }
				self.seenEvents[e.id] = true;
				self.flashes.push({ e: e, start: now + Math.random() * 120 });   // a little spread for bursts
			});
			var ids = Object.keys(this.seenEvents);
			if (ids.length > 2000) { ids.slice(0, 1000).forEach(function (k) { delete self.seenEvents[k]; }); }
			if (this.flashes.length > 400) { this.flashes = this.flashes.slice(-400); }
		}
		this.beams = this.overlays.beams ? (d.beams || []) : [];
		this.lasers = this.overlays.lasers ? (d.lasers || []) : [];
	};

	/** Where an object is now: moved on from the server's position by its velocity. */
	MapView.prototype.posOf = function (o) {
		var now = Date.now() + this.serverOffset;
		var dt = clamp((now - o.t0) / 1000, 0, MAX_EXTRAPOLATE_S);
		if (o.frozen || !dt) { return { lat: o.lat, lon: o.lon }; }
		var lat = o.lat + (o.vn || 0) * dt / EARTH * 180 / Math.PI;
		var lon = o.lon + (o.ve || 0) * dt / (EARTH * Math.max(0.01, Math.cos(o.lat * Math.PI / 180))) * 180 / Math.PI;
		return { lat: lat, lon: lon };
	};

	MapView.prototype.animating = function () {
		if (this.flashes.length || this.lasers.length) { return true; }      // lasers: the spot pulses
		if (!this.objects.length) { return false; }
		return this.followId !== null || this.objects.some(function (o) { return !o.frozen && ((o.vn || 0) || (o.ve || 0)); });
	};

	/* ---------- view ---------- */

	MapView.prototype.setView = function (lat, lon, zoom, keepFollow) {
		this.view.lat = clamp(lat, -85, 85);
		this.view.lon = ((lon + 540) % 360) - 180;
		this.view.zoom = clamp(zoom, MIN_ZOOM, MAX_ZOOM);
		if (!keepFollow && this.followId) { this.stopFollow(true); }
		this.haveView = true;
		this.dirty = true;
		this.save();
	};

	/** Zoom by steps about a screen point (default: the centre). */
	MapView.prototype.zoomBy = function (steps, sx, sy) {
		var z0 = this.view.zoom, z1 = clamp(z0 + steps, MIN_ZOOM, MAX_ZOOM);
		if (z1 === z0) { return; }
		if (sx === undefined || this.followId) { this.setView(this.view.lat, this.view.lon, z1, true); return; }
		var cx = lonToX(this.view.lon, z0), cy = latToY(this.view.lat, z0);
		var px = cx + (sx - this.W / 2), py = cy + (sy - this.H / 2);
		var lon = xToLon(px, z0), lat = yToLat(py, z0);
		var nx = lonToX(lon, z1) - (sx - this.W / 2), ny = latToY(lat, z1) - (sy - this.H / 2);
		this.setView(yToLat(ny, z1), xToLon(nx, z1), z1, true);
	};

	MapView.prototype.fitAll = function (animate) {
		if (!this.objects.length) { return; }
		var self = this;
		var lats = [], lons = [];
		this.objects.forEach(function (o) { var p = self.posOf(o); lats.push(p.lat); lons.push(p.lon); });
		var s = Math.min.apply(null, lats), n = Math.max.apply(null, lats);
		var w = Math.min.apply(null, lons), e = Math.max.apply(null, lons);
		var W = this.W || 600, H = this.H || 400;
		var z = MAX_ZOOM;
		for (; z > MIN_ZOOM; z -= 0.25) {
			if (lonToX(e, z) - lonToX(w, z) < W * 0.8 && latToY(s, z) - latToY(n, z) < H * 0.75) { break; }
		}
		z = Math.min(z, 12);
		var cy = (latToY(s, z) + latToY(n, z)) / 2;
		this.setView(yToLat(cy, z), (w + e) / 2, z, !!animate && !!this.followId ? true : false);
	};

	/** Centres on an object (or the host of a carried one) and selects it. */
	MapView.prototype.goTo = function (id, o) {
		o = o || {};
		var obj = this.byId[id], ci = this.childIndex[id];
		if (!obj && !ci) {
			if (!this.lastData || o.retry !== false && this.lastData < this.filterChanged) {
				this.pendingGoTo = id;
				this.pendingFollow = !!o.follow;
				return true;
			}
			ui.toast('It is not on the map: it has no position, or the current filter (class, search) leaves it out.', 'info', 5000);
			return false;
		}
		var target = obj || ci.parent;
		if (this.followId && !o.follow) { this.stopFollow(false); }     // another entity chosen: no "map moved" note
		var p = this.posOf(target);
		this.setView(p.lat, p.lon, Math.max(this.view.zoom, o.zoom || 9), !!o.follow);
		if (ci) { this.expanded[ci.parent.id] = true; }
		this.select({ id: id, child: !!ci });
		if (o.follow) { this.follow(target.id); }
		return true;
	};

	MapView.prototype.follow = function (id) {
		this.followId = id;
		this.updateFollowNode();
		this.renderCard();
		this.dirty = true;
	};

	MapView.prototype.stopFollow = function (byUser) {
		if (!this.followId) { return; }
		this.followId = null;
		this.updateFollowNode();
		this.renderCard();
		if (byUser) { ui.toast('Stopped following (the map was moved).', 'info', 2500); }
	};

	MapView.prototype.updateFollowNode = function () {
		var o = this.followId ? this.byId[this.followId] : null;
		this.followNode.hidden = !this.followId;
		if (!this.followId) { return; }
		var self = this;
		ui.clear(this.followNode);
		ui.append(this.followNode, [
			el('span.mv-follow-k', { text: 'FOLLOWING' }),
			el('span.mv-follow-v', { text: o ? (o.label || o.name) : 'lost - no position' }),
			el('button.btn', { type: 'button', on: { click: function () { self.stopFollow(false); } } }, '✕ Stop')
		]);
	};

	/* ---------- drawing ---------- */

	MapView.prototype.draw = function () {
		this.dirty = false;
		var c = this.ctx, W = this.W, H = this.H, dpr = this.dpr || 1;
		if (!W || !H) { this.resize(); W = this.W; H = this.H; if (!W || !H) { return; } }
		if (this.followId) {
			var fo = this.byId[this.followId];
			if (fo) { var fp = this.posOf(fo); this.view.lat = fp.lat; this.view.lon = fp.lon; }
		}
		c.setTransform(dpr, 0, 0, dpr, 0, 0);
		c.fillStyle = '#9db7c9';
		c.fillRect(0, 0, W, H);
		var z = this.view.zoom;
		var cx = lonToX(this.view.lon, z), cy = latToY(this.view.lat, z);
		if (this.map) { this.drawTiles(c, z, cx, cy); } else { this.drawGraticule(c, z, cx, cy); }
		if (this.overlayList.length) {
			this.drawOverlayTiles(c, z, cx, cy);
			this.wantOverlayLabels(z, cx, cy);
		}
		if (this.beams.length) { this.drawBeams(c, z, cx, cy); }
		if (this.lasers.length) { this.drawLasers(c, z, cx, cy, false); }   // lines below the symbols
		if (this.overlayList.length) { this.drawOverlayLabels(c, z, cx, cy); }   // place names under the entities
		this.drawObjects(c, z, cx, cy);
		if (this.lasers.length) { this.drawLasers(c, z, cx, cy, true); }    // spots above them
		if (this.flashes.length) { this.drawFlashes(c, z, cx, cy); }
		this.drawScale(z);
	};

	function freqText(hz) {
		return hz >= 1e9 ? (hz / 1e9).toFixed(3).replace(/\.?0+$/, '') + ' GHz'
			: hz >= 1e6 ? (hz / 1e6).toFixed(3).replace(/\.?0+$/, '') + ' MHz'
			: hz >= 1e3 ? (hz / 1e3).toFixed(1) + ' kHz' : Math.round(hz) + ' Hz';
	}

	/** Metres per screen pixel at a latitude and zoom (Web Mercator). */
	function metresPerPixel(lat, z) {
		return Math.cos(lat * Math.PI / 180) * 2 * Math.PI * EARTH / (TILE * Math.pow(2, z));
	}

	/* Each beam: a sector from its host, centred on the host's heading + BeamAzimuthCenter, the
	   sweep on either side. Drawn below the symbols; the selected host's beams are stronger. */
	MapView.prototype.drawBeams = function (c, z, cx, cy) {
		var self = this, W = this.W, H = this.H;
		var sel = this.selected ? (this.childIndex[this.selected.id] ? this.childIndex[this.selected.id].parent.id : this.selected.id) : null;
		this.beams.forEach(function (b) {
			var host = self.byId[b.host];
			if (!host) { return; }
			var p = self.posOf(host);
			var s = self.screen(p.lat, p.lon, z, cx, cy);
			var R = self.overlays.beamKm ? self.overlays.beamKm * 1000 / metresPerPixel(p.lat, z) : BEAM_AUTO_PX;
			if (s.x + R < 0 || s.x - R > W || s.y + R < 0 || s.y - R > H || R < 3) { return; }
			var hdgNow = typeof p.hdg === 'number' ? p.hdg : host.hdg;                 // heading extrapolated with the symbol
			var az = (typeof b.relaz === 'number' && typeof hdgNow === 'number') ? hdgNow + b.relaz : b.az;
			var half = Math.max(0.75, b.sweep || 0);
			var col = (BEAM[b.kind] || BEAM.radar).c;
			var strong = sel === b.host;
			var grad = c.createRadialGradient(s.x, s.y, 0, s.x, s.y, R);
			grad.addColorStop(0, 'rgba(' + col + ',' + (strong ? 0.42 : 0.30) + ')');
			grad.addColorStop(1, 'rgba(' + col + ',0.03)');
			c.beginPath();
			if (half >= 180) {
				c.arc(s.x, s.y, R, 0, Math.PI * 2);
			} else {
				var a0 = (az - half - 90) * Math.PI / 180, a1 = (az + half - 90) * Math.PI / 180;
				c.moveTo(s.x, s.y);
				c.arc(s.x, s.y, R, a0, a1);
				c.closePath();
			}
			c.fillStyle = grad;
			c.fill();
			c.lineWidth = strong ? 1.6 : 1;
			c.strokeStyle = 'rgba(' + col + ',' + (strong ? 0.95 : 0.7) + ')';
			c.stroke();
			if (half < 180) {                                                        // centre line
				var ac = (az - 90) * Math.PI / 180;
				c.save();
				c.setLineDash([4, 4]);
				c.beginPath();
				c.moveTo(s.x, s.y);
				c.lineTo(s.x + Math.cos(ac) * R, s.y + Math.sin(ac) * R);
				c.stroke();
				c.restore();
			}
		});
	};

	/** Where a laser starts and where its spot is now ({from, to}; from may be null). */
	MapView.prototype.laserEnds = function (l) {
		var h = l.host ? this.byId[l.host] : null;
		var from = h ? this.posOf(h) : (typeof l.fromlat === 'number' ? { lat: l.fromlat, lon: l.fromlon } : null);
		var to = { lat: l.lat, lon: l.lon };
		if (l.placed === 'target' && l.target) {                                // the spot moves with its target
			var t = this.byId[l.target] || (this.childIndex[l.target] || {}).parent;
			if (t) { to = this.posOf(t); }
		}
		return { from: from, to: to };
	};

	/* Each laser: a thin glowing line from the designator's host to the spot, with dashes running
	   towards the spot (spots=false), and a pulsing ring with a cross on the spot (spots=true).
	   The selected host's or target's lasers are stronger. */
	MapView.prototype.drawLasers = function (c, z, cx, cy, spots) {
		var self = this, W = this.W, H = this.H, now = performance.now();
		var sel = this.selected ? (this.childIndex[this.selected.id] ? this.childIndex[this.selected.id].parent.id : this.selected.id) : null;
		var col = LASER.c;
		this.lasers.forEach(function (l) {
			var ends = self.laserEnds(l);
			var b = self.screen(ends.to.lat, ends.to.lon, z, cx, cy);
			var a = ends.from ? self.screen(ends.from.lat, ends.from.lon, z, cx, cy) : null;
			var strong = sel !== null && (sel === l.host || sel === l.target || (self.selected && self.selected.id === l.id));
			if (!spots) {
				if (!a) { return; }
				if (Math.max(a.x, b.x) < 0 || Math.min(a.x, b.x) > W || Math.max(a.y, b.y) < 0 || Math.min(a.y, b.y) > H) { return; }
				c.save();
				c.lineCap = 'round';
				c.shadowColor = 'rgba(' + col + ',0.9)';
				c.shadowBlur = strong ? 8 : 5;
				c.strokeStyle = 'rgba(' + col + ',' + (strong ? 0.55 : 0.35) + ')';
				c.lineWidth = strong ? 3 : 2;
				c.beginPath();
				c.moveTo(a.x, a.y);
				c.lineTo(b.x, b.y);
				c.stroke();
				c.shadowBlur = 0;
				c.strokeStyle = 'rgba(' + col + ',0.95)';
				c.lineWidth = strong ? 1.6 : 1.1;
				c.setLineDash([9, 5]);
				c.lineDashOffset = -(now / 40) % 14;                             // runs towards the spot
				c.stroke();
				c.restore();
				return;
			}
			if (b.x < -30 || b.x > W + 30 || b.y < -30 || b.y > H + 30) { return; }
			var t = (now % LASER_PULSE_MS) / LASER_PULSE_MS;
			var onTarget = l.placed === 'target';                                  // around the target's symbol
			var r = onTarget ? 12 : 5, arm = onTarget ? 5 : 6;
			c.save();
			c.strokeStyle = 'rgba(' + col + ',' + (0.85 * (1 - t)) + ')';
			c.lineWidth = 1.5;
			c.beginPath();
			c.arc(b.x, b.y, r + 10 * t, 0, Math.PI * 2);
			c.stroke();
			c.strokeStyle = 'rgb(' + col + ')';
			c.lineWidth = strong ? 2 : 1.5;
			c.beginPath();
			c.arc(b.x, b.y, r, 0, Math.PI * 2);
			c.moveTo(b.x - r - arm, b.y); c.lineTo(b.x - r + 2, b.y);
			c.moveTo(b.x + r - 2, b.y); c.lineTo(b.x + r + arm, b.y);
			c.moveTo(b.x, b.y - r - arm); c.lineTo(b.x, b.y - r + 2);
			c.moveTo(b.x, b.y + r - 2); c.lineTo(b.x, b.y + r + arm);
			c.stroke();
			if (!onTarget) {                                                       // the spot itself
				c.fillStyle = 'rgb(' + col + ')';
				c.shadowColor = 'rgba(' + col + ',0.9)';
				c.shadowBlur = 6;
				c.beginPath();
				c.arc(b.x, b.y, 2, 0, Math.PI * 2);
				c.fill();
			}
			c.restore();
		});
	};

	/* Fire: a small yellow-orange blink. Detonation: a larger burst with a red shock ring. */
	MapView.prototype.drawFlashes = function (c, z, cx, cy) {
		var now = performance.now(), keep = [];
		for (var i = 0; i < this.flashes.length; i++) {
			var f = this.flashes[i], spec = FLASH[f.e.kind] || FLASH.fire;
			var t = (now - f.start) / spec.ms;
			if (t < 0) { keep.push(f); continue; }
			if (t >= 1) { continue; }
			keep.push(f);
			var s = this.screen(f.e.lat, f.e.lon, z, cx, cy);
			if (s.x < -60 || s.x > this.W + 60 || s.y < -60 || s.y > this.H + 60) { continue; }
			var fade = 1 - t, big = f.e.kind === 'detonation';
			c.save();
			c.globalAlpha = big ? fade * fade : fade;
			c.fillStyle = spec.c;
			c.beginPath();
			c.arc(s.x, s.y, (big ? 5 : 3) + (big ? 9 : 5) * Math.sqrt(t), 0, Math.PI * 2);
			c.fill();
			c.globalAlpha = fade;
			c.strokeStyle = spec.ring;
			c.lineWidth = big ? 1 + 3 * fade : 1 + 1.5 * fade;
			c.beginPath();
			c.arc(s.x, s.y, (big ? 6 : 4) + (big ? 30 : 13) * t, 0, Math.PI * 2);
			c.stroke();
			if (big && t > 0.15) {                                                   // second, later ring
				var t2 = (t - 0.15) / 0.85;
				c.globalAlpha = (1 - t2) * 0.6;
				c.lineWidth = 1;
				c.beginPath();
				c.arc(s.x, s.y, 6 + 44 * t2, 0, Math.PI * 2);
				c.stroke();
			}
			c.restore();
		}
		this.flashes = keep;
		if (keep.length) { this.dirty = true; }
	};

	MapView.prototype.tileZoom = function (z) {
		return clamp(Math.floor(z + 0.15), 0, this.map.maxzoom);
	};

	MapView.prototype.drawTiles = function (c, z, cx, cy) {
		var tz = this.tileZoom(z), scale = Math.pow(2, z - tz), n = 1 << tz;
		var W = this.W, H = this.H;
		var ox = cx / scale, oy = cy / scale;                 // centre in tz pixels
		var x0 = Math.floor((ox - W / 2 / scale) / TILE), x1 = Math.floor((ox + W / 2 / scale) / TILE);
		var y0 = Math.max(0, Math.floor((oy - H / 2 / scale) / TILE)), y1 = Math.min(n - 1, Math.floor((oy + H / 2 / scale) / TILE));
		var ts = TILE * scale;
		c.imageSmoothingEnabled = true;
		for (var ty = y0; ty <= y1; ty++) {
			for (var tx = x0; tx <= x1; tx++) {
				var wx = ((tx % n) + n) % n;
				var sx = (tx * TILE - ox) * scale + W / 2, sy = (ty * TILE - oy) * scale + H / 2;
				this.drawTile(c, tz, wx, ty, sx, sy, ts);
			}
		}
	};

	/** Draws a tile, or the part of a coarser loaded tile that covers it while it loads. */
	MapView.prototype.drawTile = function (c, z, x, y, sx, sy, size) {
		var img = this.tile(z, x, y, true);
		if (img) { c.drawImage(img, sx, sy, size + 0.5, size + 0.5); return; }
		for (var up = 1; up <= 5 && z - up >= 0; up++) {
			var pz = z - up, f = 1 << up;
			var p = this.tile(pz, Math.floor(x / f), Math.floor(y / f), false);
			if (p) {
				var part = TILE / f;
				c.drawImage(p, (x % f) * part, (y % f) * part, part, part, sx, sy, size + 0.5, size + 0.5);
				return;
			}
		}
	};

	MapView.prototype.tile = function (z, x, y, load) {
		var key = z + '/' + x + '/' + y;
		var t = this.tiles.get(key);
		if (t) {
			this.tiles.delete(key);
			this.tiles.set(key, t);                            // most recently used last
			return t.ok ? t.img : null;
		}
		if (!load || this.loading >= 8) { if (load) { this.dirty = true; } return null; }
		var self = this, img = new Image();
		t = { img: img, ok: false };
		this.tiles.set(key, t);
		this.loading++;
		img.onload = function () { t.ok = true; self.loading--; self.dirty = true; };
		img.onerror = function () { self.loading--; setTimeout(function () { self.tiles.delete(key); }, 5000); };
		img.src = Rift.api.endpoints.mapTile(this.map.id, z, x, y, this.map.version);
		if (this.tiles.size > 700) {
			var it = this.tiles.keys();
			for (var i = 0; i < 100; i++) { this.tiles.delete(it.next().value); }
		}
		return null;
	};

	MapView.prototype.drawGraticule = function (c, z, cx, cy) {
		var step = z < 3 ? 30 : z < 5 ? 10 : z < 7 ? 5 : z < 9 ? 1 : 0.25;
		c.strokeStyle = 'rgba(30,50,70,0.25)';
		c.lineWidth = 1;
		c.beginPath();
		for (var lon = -180; lon <= 180; lon += step) {
			var x = lonToX(lon, z) - cx + this.W / 2;
			if (x >= 0 && x <= this.W) { c.moveTo(x, 0); c.lineTo(x, this.H); }
		}
		for (var lat = -80; lat <= 80; lat += step) {
			var y = latToY(lat, z) - cy + this.H / 2;
			if (y >= 0 && y <= this.H) { c.moveTo(0, y); c.lineTo(this.W, y); }
		}
		c.stroke();
	};

	MapView.prototype.radius = function (z) { return clamp(2.2 + z * 0.55, 3.5, 10); };

	/** Screen position of a lat/lon; x moved by whole worlds to be nearest the centre. */
	MapView.prototype.screen = function (lat, lon, z, cx, cy) {
		var ww = world(z);
		var x = lonToX(lon, z) - cx;
		if (x > ww / 2) { x -= ww; } else if (x < -ww / 2) { x += ww; }
		return { x: x + this.W / 2, y: latToY(lat, z) - cy + this.H / 2 };
	};

	MapView.prototype.drawObjects = function (c, z, cx, cy) {
		var self = this, r = this.radius(z), W = this.W, H = this.H;
		var hits = [];
		this.reserved = [];                    // label boxes of fanned-out children
		var drawn = [];
		this.objects.forEach(function (o) {
			var p = self.posOf(o);
			var s = self.screen(p.lat, p.lon, z, cx, cy);
			if (s.x < -60 || s.y < -60 || s.x > W + 60 || s.y > H + 60) { return; }
			drawn.push({ o: o, x: s.x, y: s.y });
		});
		// draw order: context first, selected/followed last
		var sel = this.selected ? (this.selected.child ? (this.childIndex[this.selected.id] || {}).parent : this.byId[this.selected.id]) : null;
		drawn.sort(function (a, b) {
			var ka = (a.o === sel || a.o.id === self.followId) ? 2 : a.o.context ? 0 : 1;
			var kb = (b.o === sel || b.o.id === self.followId) ? 2 : b.o.context ? 0 : 1;
			return ka - kb;
		});
		drawn.forEach(function (d) {
			var o = d.o, x = d.x, y = d.y;
			var col = (FORCE[o.force] || FORCE.none).c;
			// speed / heading tick
			if (!o.frozen && o.gs > 0.5) {
				var len = r + clamp(5 + o.gs / 15, 6, 30), a = (o.hdg || 0) * Math.PI / 180;
				c.strokeStyle = 'rgba(15,20,25,0.85)';
				c.lineWidth = 2;
				c.beginPath();
				c.moveTo(x + Math.sin(a) * r, y - Math.cos(a) * r);
				c.lineTo(x + Math.sin(a) * len, y - Math.cos(a) * len);
				c.stroke();
			}
			if (o === sel || o.id === self.followId) {
				c.beginPath();
				c.arc(x, y, r + 5, 0, Math.PI * 2);
				c.strokeStyle = o.id === self.followId ? '#ffcc33' : '#ffffff';
				c.lineWidth = 2.5;
				c.stroke();
				if (o.id === self.followId) {
					c.beginPath();
					c.arc(x, y, r + 9, 0, Math.PI * 2);
					c.strokeStyle = 'rgba(255,204,51,0.6)';
					c.lineWidth = 1.5;
					c.stroke();
				}
			}
			c.beginPath();
			c.arc(x, y, r, 0, Math.PI * 2);
			if (o.context) {
				c.fillStyle = 'rgba(255,255,255,0.75)';
				c.fill();
				c.setLineDash([3, 2]);
				c.strokeStyle = col;
				c.lineWidth = 2;
				c.stroke();
				c.setLineDash([]);
			} else {
				c.fillStyle = col;
				c.fill();
				c.strokeStyle = 'rgba(10,15,20,0.85)';
				c.lineWidth = 1.5;
				c.stroke();
			}
			hits.push({ id: o.id, x: x, y: y, r: r + 3, kind: 'object' });
			var kids = o.children || [];
			if (kids.length) {
				var bx = x + r * 0.75, by = y - r - 7;
				var txt = (self.expanded[o.id] ? '−' : '+') + kids.length;
				c.font = '700 10px ' + monoFont();
				var bw = c.measureText(txt).width + 8;
				c.fillStyle = self.expanded[o.id] ? '#ffcc33' : '#1d2329';
				roundRect(c, bx - 2, by - 7, bw, 13, 3);
				c.fill();
				c.fillStyle = self.expanded[o.id] ? '#1d2329' : '#ffffff';
				c.fillText(txt, bx + 2, by + 3.5);
				hits.push({ id: o.id, x: bx - 2 + bw / 2, y: by - 0.5, r: Math.max(9, bw / 2 + 2), kind: 'badge' });
				if (self.expanded[o.id]) { self.drawChildren(c, o, x, y, r, kids, hits); }
			}
			d.r = r;
		});
		// labels last, without overlaps (selected / followed first; children's labels already placed)
		var placed = this.reserved.slice();
		var showAll = this.labels === 'all' || (this.labels === 'auto' && (z >= 6 || drawn.length <= 40));
		var order = drawn.slice().reverse();
		order.forEach(function (d) {
			var o = d.o;
			var must = o === sel || o.id === self.followId || o.id === self.hoverId;
			if (self.labels === 'off' && !must) { return; }
			if (!showAll && !must) { return; }
			var lines = [o.label || shortName(o.name), [o.entityid, o['class']].filter(Boolean).join(' · ')];
			if (o.context) { lines[1] = (lines[1] ? lines[1] + ' · ' : '') + 'outside the filter'; }
			var lx = d.x + d.r + 5, ly = d.y - 3;
			c.font = '700 11.5px ' + monoFont();
			var w1 = c.measureText(lines[0] || '').width;
			c.font = '10px ' + monoFont();
			var w2 = c.measureText(lines[1] || '').width;
			var box = { x: lx - 2, y: ly - 11, w: Math.max(w1, w2) + 4, h: 26 };
			if (!must && placed.some(function (b) { return !(box.x > b.x + b.w || box.x + box.w < b.x || box.y > b.y + b.h || box.y + box.h < b.y); })) { return; }
			placed.push(box);
			haloText(c, lines[0] || '', lx, ly, '700 11.5px ' + monoFont(), '#10161b');
			if (lines[1]) { haloText(c, lines[1], lx, ly + 12, '10px ' + monoFont(), '#2c3640'); }
		});
		this.hits = hits;
	};

	MapView.prototype.drawChildren = function (c, o, x, y, r, kids, hits) {
		var n = kids.length;
		var ring = r + 26 + Math.max(0, n - 8) * 3;
		var self = this;
		kids.forEach(function (k, i) {
			var a = -Math.PI * 5 / 6 + i * 2 * Math.PI / Math.max(n, 1);     // first at upper left: the badge is upper right
			var kx = x + Math.cos(a) * ring, ky = y + Math.sin(a) * ring;
			c.strokeStyle = 'rgba(20,25,30,0.7)';
			c.lineWidth = 1.2;
			c.beginPath();
			c.moveTo(x + Math.cos(a) * r, y + Math.sin(a) * r);
			c.lineTo(kx, ky);
			c.stroke();
			var selK = self.selected && self.selected.id === k.id;
			c.fillStyle = selK ? '#ffcc33' : '#ffffff';
			c.strokeStyle = '#1d2329';
			c.lineWidth = 1.5;
			c.fillRect(kx - 4.5, ky - 4.5, 9, 9);
			c.strokeRect(kx - 4.5, ky - 4.5, 9, 9);
			var right = Math.cos(a) >= -0.1;
			var t1 = k.label || shortName(k.name), t2 = k['class'] + (k.relation === 'partof' ? ' (part)' : '');
			c.font = '700 10.5px ' + monoFont();
			var tw = Math.max(c.measureText(t1).width, c.measureText(t2).width * 0.95) + 4;
			c.textAlign = right ? 'left' : 'right';
			haloText(c, t1, kx + (right ? 8 : -8), ky - 1, '700 10.5px ' + monoFont(), '#10161b');
			haloText(c, t2, kx + (right ? 8 : -8), ky + 10, '9.5px ' + monoFont(), '#2c3640');
			c.textAlign = 'left';
			self.reserved.push({ x: right ? kx + 6 : kx - 8 - tw, y: ky - 12, w: tw + 4, h: 26 });
			hits.push({ id: k.id, x: kx, y: ky, r: 8, kind: 'child', parent: o.id });
		});
	};

	function shortName(n) {
		var s = String(n || '');
		return s.length > 28 ? '…' + s.slice(-27) : s;
	}

	var MONO = null;
	function monoFont() {
		if (!MONO) {
			MONO = (getComputedStyle(document.documentElement).getPropertyValue('--mono') || 'monospace').trim() || 'monospace';
		}
		return MONO;
	}

	function haloText(c, t, x, y, font, fill) {
		c.font = font;
		c.lineJoin = 'round';
		c.lineWidth = 3.5;
		c.strokeStyle = 'rgba(255,255,255,0.88)';
		c.strokeText(t, x, y);
		c.fillStyle = fill;
		c.fillText(t, x, y);
	}

	function roundRect(c, x, y, w, h, r) {
		c.beginPath();
		c.moveTo(x + r, y);
		c.lineTo(x + w - r, y);
		c.quadraticCurveTo(x + w, y, x + w, y + r);
		c.lineTo(x + w, y + h - r);
		c.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
		c.lineTo(x + r, y + h);
		c.quadraticCurveTo(x, y + h, x, y + h - r);
		c.lineTo(x, y + r);
		c.quadraticCurveTo(x, y, x + r, y);
		c.closePath();
	}

	MapView.prototype.drawScale = function (z) {
		var mpp = Math.cos(this.view.lat * Math.PI / 180) * 2 * Math.PI * EARTH / world(z);
		var target = 110 * mpp;
		var p = Math.pow(10, Math.floor(Math.log(target) / Math.LN10));
		var nice = [1, 2, 5, 10].map(function (k) { return k * p; }).filter(function (v) { return v <= target; }).pop() || p;
		var px = nice / mpp;
		this.scaleNode.firstChild.style.width = Math.round(px) + 'px';
		this.scaleNode.lastChild.textContent = nice >= 1000 ? (nice / 1000) + ' km' : nice + ' m';
	};

	/* ---------- selection and the card ---------- */

	MapView.prototype.select = function (sel) {
		this.selected = sel;
		this.renderCard();
		this.dirty = true;
		if (this.opts.onSelect) { this.opts.onSelect(sel); }
	};

	MapView.prototype.renderCard = function () {
		var self = this, card = this.card;
		var s = this.selected;
		if (!s) { card.hidden = true; return; }
		var ci = s.child ? this.childIndex[s.id] : null;
		var o = s.child ? (ci && ci.child) : this.byId[s.id];
		if (!o) { card.hidden = true; return; }
		card.hidden = false;
		ui.clear(card);
		var host = s.child ? ci.parent : o;
		var force = FORCE[host.force] || FORCE.none;
		var head = el('div.mv-card-head', null, [
			el('i.mv-dot', { style: 'background:' + force.c }),
			el('span.mv-card-name', { text: o.label || shortName(o.name), title: o.name }),
			el('span.mv-card-class', { text: o['class'] }),
			el('button.mv-x', { type: 'button', title: 'Close', 'aria-label': 'Close', on: { click: function () { self.select(null); } } }, '✕')
		]);
		card.appendChild(head);
		var rows = [];
		if (s.child) {
			var isDesignator = /(^|\.)Designator$/.test(o.classnameq || '') || o['class'] === 'Designator';
			rows.push(['On', (ci.parent.label || shortName(ci.parent.name)) + (o.relation === 'partof' ? ' (part of it)' : o.relation === 'beam' ? ' (beam of its emitter)'
				: isDesignator ? ' (its laser designator)' : ' (carried)')]);
			if (o.entityid) { rows.push(['Entity id', o.entityid]); }
			rows.push(['Name', o.name]);
			var bm = this.beams.filter(function (b) { return b.id === o.id; })[0];
			if (bm) {
				if (bm.emitter) { rows.push(['Emitter', bm.emitter + (bm.emitterfunction ? ' · ' + bm.emitterfunction : '')]); }
				if (bm['function']) { rows.push(['Function', bm['function']]); }
				rows.push(['Azimuth', Math.round(bm.az) + '° true (' + (bm.relaz >= 0 ? '+' : '') + Math.round(bm.relaz) + '° from the heading), ±' + (Math.round(bm.sweep * 10) / 10) + '°']);
				if (typeof bm.el === 'number') { rows.push(['Elevation', (Math.round(bm.el * 10) / 10) + '°' + (typeof bm.elsweep === 'number' ? ' ±' + (Math.round(bm.elsweep * 10) / 10) + '°' : '')]); }
				if (bm.freq) { rows.push(['Frequency', freqText(bm.freq) + (bm.prf ? ' · PRF ' + Math.round(bm.prf) + ' Hz' : '')]); }
				if (typeof bm.erp === 'number') { rows.push(['ERP', bm.erp + ' dBm']); }
			} else if (o.relation === 'beam' && !this.overlays.beams) {
				rows.push(['', 'Turn on "Beams" to see it on the map.']);
			}
			var lz = this.lasers.filter(function (l) { return l.id === o.id; })[0];
			if (lz) {
				rows.push(['Lasing', lz.targetlabel ? lz.targetlabel + (lz.placed === 'target' ? '' : ' (spot near it)') : 'a spot, no designated object']);
				var sp = this.laserEnds(lz).to;
				rows.push(['Spot', dms(sp.lat, 'N', 'S') + '  ' + dms(sp.lon, 'E', 'W')]);
				if (typeof lz.range === 'number') { rows.push(['Range', lz.range >= 10000 ? (lz.range / 1000).toFixed(1) + ' km' : Math.round(lz.range) + ' m']); }
				if (lz.code || lz.codename) { rows.push(['Code', [lz.code, lz.codename].filter(Boolean).join(' · ')]); }
				if (typeof lz.power === 'number') { rows.push(['Power', lz.power + ' W']); }
				if (typeof lz.wavelength === 'number') { rows.push(['Wavelength', lz.wavelength + ' µm']); }
			} else if (isDesignator) {
				rows.push(['', this.overlays.lasers ? 'Not lasing (power 0), or the spot has no position.' : 'Turn on "Lasers" to see it on the map.']);
			}
		} else {
			if (o.entityid) { rows.push(['Entity id', o.entityid]); }
			rows.push(['Force', o.forcetext || force.l]);
			var p = this.posOf(o);
			rows.push(['Position', dms(p.lat, 'N', 'S') + '  ' + dms(p.lon, 'E', 'W')]);
			rows.push(['', p.lat.toFixed(5) + ', ' + p.lon.toFixed(5)]);
			rows.push(['Altitude', Math.round(o.alt) + ' m  (' + Math.round(o.alt / 0.3048) + ' ft)']);
			rows.push(['Heading', Math.round(o.hdg) + '°']);
			rows.push(['Speed', o.gs.toFixed(1) + ' m/s  (' + Math.round(o.gs * 1.943844) + ' kn, ' + Math.round(o.gs * 3.6) + ' km/h)']);
			if (o.age !== null && o.age !== undefined) { rows.push(['Updated', o.age < 1.5 ? 'now' : Math.round(o.age) + ' s ago' + (o.age > 30 ? ' (no longer moved on)' : '')]); }
			if (o.context) { rows.push(['', 'Not in the current filter; shown as the host of a matching object.']); }
			this.lasers.forEach(function (l) {
				if (l.host === o.id) { rows.push(['Lasing', l.targetlabel || 'a spot, no designated object']); }
				if (l.target === o.id) { rows.push(['Designated by', l.hostlabel || l.label]); }
			});
		}
		card.appendChild(el('dl.mv-card-rows', null, rows.reduce(function (acc, r) {
			acc.push(el('dt', { text: r[0] }));
			acc.push(el('dd', { text: r[1] }));
			return acc;
		}, [])));
		if (!s.child && (o.children || []).length) {
			var open = !!this.expanded[o.id];
			card.appendChild(el('div.mv-card-kids', null, [
				el('button.mv-kids-toggle', { type: 'button', 'aria-expanded': open ? 'true' : 'false',
					on: { click: function () { self.expanded[o.id] = !open; self.renderCard(); self.dirty = true; } } },
					(open ? '▾ ' : '▸ ') + 'Carries ' + o.children.length + (open ? ' (hide on the map)' : ' (show on the map)')),
				open ? el('ul.mv-kids', null, o.children.map(function (k) {
					return el('li', null, el('button', { type: 'button', on: { click: function () { self.select({ id: k.id, child: true }); } } }, [
						el('span.k', { text: k.label || shortName(k.name) }),
						el('span.c', { text: k['class'] + (k.relation === 'partof' ? ' · part' : k.relation === 'beam' ? ' · beam' : '') })
					]));
				})) : null
			]));
		}
		var following = this.followId === host.id;
		card.appendChild(el('div.mv-card-acts', null, [
			el('button.btn', { type: 'button', title: 'Centre the map on it', on: { click: function () {
				var hp = self.posOf(host);
				self.setView(hp.lat, hp.lon, Math.max(self.view.zoom, 9));
			} } }, '⌖ Center'),
			el('button.btn' + (following ? '.on' : ''), { type: 'button', title: 'Keep it in the centre', on: { click: function () {
				if (following) { self.stopFollow(false); } else { self.follow(host.id); }
			} } }, following ? '◉ Following' : '◎ Follow'),
			this.opts.onOpenObject ? el('button.btn', { type: 'button', title: 'All attributes in the object list', on: { click: function () {
				self.opts.onOpenObject({ instanceid: o.id, label: o.label, classname: o.classnameq || o['class'] });
			} } }, '☰ Details') : null,
			s.child ? el('button.btn', { type: 'button', on: { click: function () { self.select({ id: ci.parent.id, child: false }); } } }, '↑ Host') : null
		]));
	};

	/* ---------- pointer, wheel, keys ---------- */

	MapView.prototype.hitAt = function (x, y) {
		var best = null, bd = Infinity;
		for (var i = this.hits.length - 1; i >= 0; i--) {
			var h = this.hits[i], d = Math.hypot(h.x - x, h.y - y);
			if (d <= h.r && d < bd + (h.kind === 'object' ? 0 : 3)) { best = h; bd = d; }
		}
		return best;
	};

	MapView.prototype.wirePointer = function () {
		var self = this, cv = this.canvas;
		var pts = new Map(), start = null, moved = false, pinch = null;
		function local(e) { var r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
		cv.addEventListener('pointerdown', function (e) {
			cv.focus({ preventScroll: true });
			cv.setPointerCapture(e.pointerId);
			pts.set(e.pointerId, local(e));
			if (pts.size === 1) {
				start = { p: local(e), lat: self.view.lat, lon: self.view.lon, z: self.view.zoom };
				moved = false;
			} else if (pts.size === 2) {
				var a = Array.from(pts.values());
				pinch = { d: Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y), z: self.view.zoom };
			}
		});
		cv.addEventListener('pointermove', function (e) {
			var p = local(e);
			var z = self.view.zoom, cx = lonToX(self.view.lon, z), cy = latToY(self.view.lat, z);
			var lat = yToLat(cy + p.y - self.H / 2, z), lon = xToLon(cx + p.x - self.W / 2, z);
			self.cursorNode.textContent = fmtLat(lat) + ' ' + fmtLon(((lon + 540) % 360) - 180);
			if (!pts.has(e.pointerId)) {
				var h = self.hitAt(p.x, p.y);
				cv.style.cursor = h ? 'pointer' : 'grab';
				var hid = h && h.kind === 'object' ? h.id : null;
				if (hid !== self.hoverId) { self.hoverId = hid; self.dirty = true; }
				return;
			}
			pts.set(e.pointerId, p);
			if (pts.size === 2 && pinch) {
				var a = Array.from(pts.values());
				var d = Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y);
				if (pinch.d > 10) {
					var nz = clamp(pinch.z + Math.log(d / pinch.d) / Math.LN2, MIN_ZOOM, MAX_ZOOM);
					self.zoomBy(nz - self.view.zoom, (a[0].x + a[1].x) / 2, (a[0].y + a[1].y) / 2);
				}
				moved = true;
				return;
			}
			if (!start) { return; }
			var dx = p.x - start.p.x, dy = p.y - start.p.y;
			if (!moved && Math.hypot(dx, dy) < 4) { return; }
			moved = true;
			cv.style.cursor = 'grabbing';
			var sz = start.z;
			var sx = lonToX(start.lon, sz) - dx, sy = latToY(start.lat, sz) - dy;
			self.setView(yToLat(sy, sz), xToLon(sx, sz), sz);
		});
		function up(e) {
			if (!pts.has(e.pointerId)) { return; }
			var p = local(e);
			pts.delete(e.pointerId);
			if (pts.size < 2) { pinch = null; }
			if (pts.size === 0) {
				cv.style.cursor = 'grab';
				if (!moved && e.type === 'pointerup') { self.click(p.x, p.y); }
				start = null;
			}
		}
		cv.addEventListener('pointerup', up);
		cv.addEventListener('pointercancel', up);
		cv.addEventListener('pointerleave', function () { if (self.hoverId) { self.hoverId = null; self.dirty = true; } });
		cv.addEventListener('wheel', function (e) {
			e.preventDefault();
			var p = local(e);
			var delta = e.deltaMode === 1 ? e.deltaY * 33 : e.deltaMode === 2 ? e.deltaY * 400 : e.deltaY;
			self.zoomBy(clamp(-delta * 0.0022, -1, 1), p.x, p.y);
		}, { passive: false });
		cv.addEventListener('dblclick', function (e) {
			var p = local(e);
			if (!self.hitAt(p.x, p.y)) { self.zoomBy(e.shiftKey ? -1 : 1, p.x, p.y); }
		});
	};

	MapView.prototype.click = function (x, y) {
		var h = this.hitAt(x, y);
		if (!h) { this.select(null); return; }
		if (h.kind === 'badge') {
			this.expanded[h.id] = !this.expanded[h.id];
			this.select({ id: h.id, child: false });
			return;
		}
		this.select({ id: h.id, child: h.kind === 'child' });
	};

	MapView.prototype.wireKeys = function () {
		var self = this;
		this.canvas.addEventListener('keydown', function (e) {
			var step = 80;
			var z = self.view.zoom, cx = lonToX(self.view.lon, z), cy = latToY(self.view.lat, z);
			var pan = function (dx, dy) { e.preventDefault(); self.setView(yToLat(cy + dy, z), xToLon(cx + dx, z), z); };
			switch (e.key) {
				case '+': case '=': e.preventDefault(); self.zoomBy(1); break;
				case '-': case '_': e.preventDefault(); self.zoomBy(-1); break;
				case 'ArrowLeft': pan(-step, 0); break;
				case 'ArrowRight': pan(step, 0); break;
				case 'ArrowUp': pan(0, -step); break;
				case 'ArrowDown': pan(0, step); break;
				case 'f': case 'F':
					if (self.selected) {
						var host = self.selected.child ? self.childIndex[self.selected.id].parent : self.byId[self.selected.id];
						if (host) { if (self.followId === host.id) { self.stopFollow(false); } else { self.follow(host.id); } }
					}
					break;
				case 'Escape': if (self.followId) { self.stopFollow(false); } else { self.select(null); } break;
				default: return;
			}
		});
	};

	/* ---------- search ---------- */

	MapView.prototype.wireSearch = function () {
		var self = this, input = this.search, list = this.results;
		var found = [];
		function run() {
			var q = input.value.trim().toLowerCase();
			ui.clear(list);
			found = [];
			if (!q) { list.hidden = true; return; }
			var add = function (o, parent) {
				var hay = [o.label, o.name, o.entityid, o['class']].filter(Boolean).join(' ').toLowerCase();
				if (hay.indexOf(q) >= 0) { found.push({ o: o, parent: parent }); }
			};
			self.objects.forEach(function (o) {
				add(o, null);
				(o.children || []).forEach(function (k) { add(k, o); });
			});
			found = found.slice(0, 12);
			found.forEach(function (f, i) {
				var host = f.parent || f.o;
				list.appendChild(el('li', { role: 'option' }, el('button', { type: 'button', on: { click: function () { pick(i); } } }, [
					el('i.mv-dot', { style: 'background:' + (FORCE[host.force] || FORCE.none).c }),
					el('span.k', { text: f.o.label || shortName(f.o.name) }),
					el('span.c', { text: [f.o.entityid, f.o['class'], f.parent ? 'on ' + (f.parent.label || shortName(f.parent.name)) : null].filter(Boolean).join(' · ') })
				])));
			});
			if (!found.length) { list.appendChild(el('li.none', { text: 'No entity on the map matches' })); }
			list.hidden = false;
		}
		function pick(i) {
			var f = found[i];
			if (!f) { return; }
			list.hidden = true;
			input.value = '';
			self.goTo(f.o.id);
			self.canvas.focus({ preventScroll: true });
		}
		input.addEventListener('input', run);
		input.addEventListener('keydown', function (e) {
			if (e.key === 'Enter') { e.preventDefault(); pick(0); }
			if (e.key === 'Escape') { input.value = ''; list.hidden = true; }
		});
		input.addEventListener('blur', function () { setTimeout(function () { list.hidden = true; }, 200); });
		input.addEventListener('focus', function () { if (input.value.trim()) { run(); } });
	};

	MapView.available = function () { return maps().then(function (m) { return m.enabled; }); };

	Rift.MapView = MapView;
})(window, document);
