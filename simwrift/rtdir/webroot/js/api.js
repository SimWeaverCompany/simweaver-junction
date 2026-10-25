/* ============================================================
   SimWeaver Rift - server communication.
   All REST endpoints are declared in one place (Rift.api.endpoints)
   so the full REST API can be wired in later without touching
   the page scripts. Also contains a small SSE helper for the
   live updates that will come later.
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift = window.Rift || {};

	/** Prefix for every API call. Empty = same host and port as the page. */
	var BASE = '';

	var DEFAULT_TIMEOUT_MS = 10000;

	/**
	 * Query string. Array values are joined with commas (class=A,B): the xrest request
	 * handler keeps only one value per key. null/undefined are left out.
	 */
	function query(params) {
		var parts = [];
		Object.keys(params || {}).forEach(function (k) {
			var v = params[k];
			if (v === undefined || v === null) { return; }
			if (Array.isArray(v)) { v = v.join(','); }
			parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
		});
		return parts.join('&');
	}

	var endpoints = {
		/* GET  -> {"serverinfo":{"name","version","uptimeseconds","cpu":{"process","system","cores"},
		            "memory":{"heapused","heapmax",...},"threads","datasources",...}}   (top bar) */
		serverInfo:        function () { return BASE + '/api/serverinfo'; },
		/* GET  -> {"datasourceconfigs":[{...}]} */
		datasourceConfigs: function () { return BASE + '/api/datasourceconfigs'; },
		/* GET  -> {"datasources":[{"id":..,"name":".."}]}
		   POST <- {"configid":.., "federation":".."}   (create a datasource) */
		datasources:       function () { return BASE + '/api/datasources'; },
		/* GET    -> one datasource (assumed - adjust when the full REST API is defined)
		   DELETE -> resign, disconnect and remove it (204, or 200 {"datasource":{...}}) */
		datasource:        function (id) { return BASE + '/api/datasources/' + encodeURIComponent(id); },
		/* Datasource page (docs/datasource-page-contract.md) */
		dsClasses:        function (ds) { return endpoints.datasource(ds) + '/classes'; },
		dsObjects:        function (ds, params) { return endpoints.datasource(ds) + '/objects?' + query(params); },
		dsObject:         function (ds, iid, view) {
			return endpoints.datasource(ds) + '/objects/' + encodeURIComponent(iid) + '?' + query({ view: view || 'full' });
		},
		dsInteractions:   function (ds, params) { return endpoints.datasource(ds) + '/interactions?' + query(params); },
		dsInteraction:    function (ds, id) { return endpoints.datasource(ds) + '/interactions/' + encodeURIComponent(id); },
		/* Map (NEW): GET -> {"seq","now","matched","shown","children","unpositioned","objects":[{id,label,lat,lon,...,children:[]}]} */
		dsMap:            function (ds, params) { return endpoints.datasource(ds) + '/map?' + query(params); },
		/* GET -> {"enabled","maps":[{id,name,bounds,minzoom,maxzoom,tiles}],"default","problems"} */
		maps:             function () { return BASE + '/api/maps'; },
		/* version: the map's "version" from GET /api/maps; a new one when the file changes, so the
		   browser does not keep showing tiles of the old file from its cache */
		/* NEW (map overlays): ov.tiles is "maps/overlays/<id>/tiles/{z}/{x}/{y}.png?v=..." from GET /api/maps */
		overlayTile:      function (ov, z, x, y) { return BASE + '/api/' + ov.tiles.replace('{z}', z).replace('{x}', x).replace('{y}', y); },
		overlayLabels:    function (ov, bbox, zoom) { return BASE + '/api/' + ov.labels + '?bbox=' + bbox.map(function (v) { return v.toFixed(4); }).join(',') + '&zoom=' + zoom; },
		mapTile:          function (id, z, x, y, version) { return BASE + '/api/maps/' + encodeURIComponent(id) + '/tiles/' + z + '/' + x + '/' + y + '.jpg' + (version ? '?v=' + encodeURIComponent(version) : ''); },
		/* XrSseHub endpoint: GET /api/events?topics=a,b,c (topics fixed per connection).
		   ':' is left unencoded (allowed in a query) to keep long topic lists short. */
		events:           function (topics) {
			return BASE + '/api/events?topics=' + topics.map(function (t) {
				return encodeURIComponent(t).replace(/%3A/gi, ':');
			}).join(',');
		},

		/* GET  -> {"federations":["name", ...]}   ({"federations":[]} when none found)
		   Federations available for a config. The result depends on the config's
		   standard + LSD, which are sent along as query parameters. */
		autoFederations:   function (configId, standard, lsd) {
			return BASE + '/api/datasourceconfig/' + encodeURIComponent(configId) + '/autofederations?' +
				query({ standard: standard, lsd: lsd });
		}
	};

	/**
	 * Error carrying the HTTP status (0 = network error / timeout), and when the
	 * server sent one, its error code and the full unshortened message.
	 */
	function ApiError(message, status, url, code, detail) {
		this.name = 'ApiError';
		this.message = message;
		this.status = status;
		this.url = url;
		this.code = code || null;
		this.detail = detail || null;
	}
	ApiError.prototype = Object.create(Error.prototype);
	ApiError.prototype.constructor = ApiError;

	/* Simple listener list so the page can show a global link indicator. */
	var linkListeners = [];
	function notifyLink(ok, url, error) {
		linkListeners.forEach(function (fn) {
			try { fn(ok, url, error); } catch (e) { /* listener problems must not break calls */ }
		});
	}

	function str(v) { return (typeof v === 'string' || typeof v === 'number') ? String(v).trim() : ''; }

	/**
	 * "hla.rti1516_2025.exceptions.ConnectionFailed: Address ..." ->
	 * "ConnectionFailed: Address ..."  (drops a leading Java package name)
	 */
	function shortenJavaClass(message) {
		return message.replace(/^(?:[a-z_$][\w$]*\.)+(?=[A-Z][\w$]*(?::|$))/, '');
	}

	/**
	 * Reads an error description out of a parsed reply object. Understands:
	 *   {"error":"text"}
	 *   {"error":{"code":"X","message":"text"}}      (also "reason"/"detail")
	 *   {"message":"text", "code":"X"}
	 * @returns {{message:string, code:string, detail:string}|null}
	 */
	function errorInfo(obj) {
		if (!obj || typeof obj !== 'object') { return null; }
		var err = obj.error;
		var message = '', code = str(obj.code);

		if (err && typeof err === 'object') {
			message = str(err.message) || str(err.reason) || str(err.detail) || str(err.description);
			code = str(err.code) || code;
		} else {
			message = str(err);
		}
		message = message || str(obj.message) || str(obj.reason) || str(obj.detail);

		if (!message && !code) { return null; }
		var full = message;
		return { message: shortenJavaClass(message), code: code, detail: full };
	}

	/** Same as errorInfo, from the raw reply text (JSON or short plain text). */
	function errorInfoFromText(text) {
		if (!text) { return null; }
		try { return errorInfo(JSON.parse(text)); }
		catch (e) { /* not JSON */ }
		var plain = text.trim();
		return (plain.length <= 200 && plain.indexOf('<') === -1 && plain)
			? { message: shortenJavaClass(plain), code: '', detail: plain } : null;
	}

	/**
	 * Performs a request and parses the reply as JSON.
	 * An empty reply body (e.g. 201/204 without content) resolves to null.
	 * @param {string} method  'GET', 'POST', ...
	 * @param {string} url
	 * @param {*} [body]       sent as JSON when given
	 * @param {{timeoutMs?:number}} [options]
	 * @returns {Promise<*>} rejects with ApiError
	 */
	function request(method, url, body, options) {
		var timeoutMs = (options && options.timeoutMs) || DEFAULT_TIMEOUT_MS;
		var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
		var timer = controller ? setTimeout(function () { controller.abort(); }, timeoutMs) : null;

		var init = {
			method: method,
			headers: { 'Accept': 'application/json' },
			cache: 'no-store',
			signal: controller ? controller.signal : undefined
		};
		if (body !== undefined) {
			init.headers['Content-Type'] = 'application/json';
			init.body = JSON.stringify(body);
		}

		return fetch(url, init).then(function (response) {
			return response.text().then(function (text) {
				var httpText = 'Server answered HTTP ' + response.status +
					(response.statusText ? ' ' + response.statusText : '');
				if (!response.ok) {
					var info = errorInfoFromText(text);
					throw new ApiError((info && info.message) || httpText, response.status, url,
						info && info.code, info && info.detail);
				}
				if (!text) { return null; }
				var data;
				try { data = JSON.parse(text); }
				catch (e) { throw new ApiError('Server reply was not valid JSON', response.status, url); }

				// A 2xx reply that still says {"success":false, ...} is a failure too.
				if (data && typeof data === 'object' && data.success === false) {
					var fail = errorInfo(data);
					throw new ApiError((fail && fail.message) || 'Server reported failure (' + httpText + ')',
						response.status, url, fail && fail.code, fail && fail.detail);
				}
				return data;
			});
		}, function (err) {
			var aborted = err && err.name === 'AbortError';
			throw new ApiError(aborted ? 'No reply within ' + (timeoutMs / 1000) + ' s' : 'Server not reachable', 0, url);
		}).then(function (data) {
			if (timer) { clearTimeout(timer); }
			notifyLink(true, url, null);
			return data;
		}, function (err) {
			if (timer) { clearTimeout(timer); }
			if (!(err instanceof ApiError)) {   // e.g. aborted while reading the body
				err = new ApiError('Connection interrupted', 0, url);
			}
			// A 404 means the server is up - only network failures count as link loss.
			notifyLink(err.status !== 0, url, err);
			throw err;
		});
	}

	/**
	 * Subscribe to a Server-Sent Events stream. The browser reconnects
	 * automatically; handlers are optional.
	 * @param {string} url
	 * @param {{onMessage?:Function, events?:Object<string,Function>, onOpen?:Function, onError?:Function}} handlers
	 *        events: map of named SSE events ("event: xyz") to handler(data, rawEvent)
	 * @returns {Function} call it to close the stream
	 */
	function subscribe(url, handlers) {
		handlers = handlers || {};
		if (typeof EventSource === 'undefined') {
			if (handlers.onError) { handlers.onError(new Error('EventSource not supported')); }
			return function () {};
		}
		var source = new EventSource(url);

		function parse(raw) {
			try { return JSON.parse(raw); } catch (e) { return raw; }
		}

		source.addEventListener('open', function () {
			notifyLink(true, url, null);
			if (handlers.onOpen) { handlers.onOpen(); }
		});
		source.addEventListener('error', function (e) {
			// Stream dropped (browser is reconnecting, or gave up).
			notifyLink(false, url, e);
			if (handlers.onError) { handlers.onError(e); }
		});
		if (handlers.onMessage) {
			source.addEventListener('message', function (e) { handlers.onMessage(parse(e.data), e); });
		}
		Object.keys(handlers.events || {}).forEach(function (name) {
			source.addEventListener(name, function (e) { handlers.events[name](parse(e.data), e); });
		});

		return function close() { source.close(); };
	}

	function getJson(url, options) { return request('GET', url, undefined, options); }
	function postJson(url, body, options) { return request('POST', url, body, options); }

	Rift.api = {
		endpoints: endpoints,
		/** Lets other transports (the live stream) drive the link indicator. */
		reportLink: function (ok, url) { notifyLink(ok, url, null); },
		ApiError: ApiError,
		request: request,
		getJson: getJson,
		postJson: postJson,
		subscribe: subscribe,
		onLinkChange: function (fn) { linkListeners.push(fn); },

		/** @returns {Promise<Array<Object>>} full config objects */
		fetchDatasourceConfigs: function () {
			return getJson(endpoints.datasourceConfigs()).then(function (data) {
				return (data && Array.isArray(data.datasourceconfigs)) ? data.datasourceconfigs : [];
			});
		},

		/** @returns {Promise<Array<{id:*, name:string}>>} running datasources */
		fetchDatasources: function () {
			return getJson(endpoints.datasources()).then(function (data) {
				return (data && Array.isArray(data.datasources)) ? data.datasources : [];
			});
		},

		/**
		 * Federations available for a configuration (depends on its standard + LSD).
		 * Accepts plain strings or objects with a "name" field.
		 * @returns {Promise<Array<string>>} sorted, without duplicates
		 */
		fetchAutoFederations: function (configId, standard, lsd) {
			return getJson(endpoints.autoFederations(configId, standard, lsd)).then(function (data) {
				var list = (data && Array.isArray(data.federations)) ? data.federations : [];
				var seen = {};
				return list.map(function (f) {
					return (f && typeof f === 'object') ? String(f.name || '') : String(f);
				}).filter(function (name) {
					if (!name || seen[name]) { return false; }
					seen[name] = true;
					return true;
				}).sort(function (a, b) { return a.localeCompare(b); });
			});
		},

		/**
		 * Creates (starts) a new datasource from a configuration.
		 * @param {*} configId     id of the datasource configuration
		 * @param {string} federation  federation to join (non-empty)
		 * @returns {Promise<{id:*, name:string}|null>} the new datasource if the server returns it
		 */
		/** lsd (0.9.15): another LSD than the configuration's default; undefined = the default */
		createDatasource: function (configId, federation, lsd) {
			var body = { configid: configId };
			if (federation !== undefined && federation !== null) { body.federation = federation; }
			if (lsd !== undefined && lsd !== null) { body.lsd = lsd; }
			return postJson(endpoints.datasources(), body, { timeoutMs: 30000 })
				.then(function (data) {
					if (data && data.datasource && typeof data.datasource === 'object') { return data.datasource; }
					return (data && data.id !== undefined) ? data : null;
				});
		},

		/**
		 * Disconnects and removes a running datasource.
		 * A 404 is reported as success with {alreadyGone:true} - but only after
		 * checking that the id really is no longer listed, since a 404 can also mean
		 * the server has no DELETE route yet.
		 * @returns {Promise<{removed:Object|null, alreadyGone:boolean}>}
		 */
		removeDatasource: function (id) {
			var url = endpoints.datasource(id);
			return request('DELETE', url, undefined, { timeoutMs: 30000 })
				.then(function (data) {
					var removed = (data && data.datasource && typeof data.datasource === 'object') ? data.datasource : null;
					return { removed: removed, alreadyGone: false };
				}, function (err) {
					if (!err || err.status !== 404) { throw err; }
					return Rift.api.fetchDatasources().then(function (list) {
						var stillThere = list.some(function (ds) { return ds && String(ds.id) === String(id); });
						if (stillThere) {
							throw new ApiError('The server does not support removing datasources yet', 404, url, err.code, err.detail);
						}
						return { removed: null, alreadyGone: true };
					}, function () { throw err; });
				});
		},

		/* ---------- Datasource page ---------- */

		/** @returns {Promise<{seq, total, classes:[{classnameq,count}]}>} */
		fetchClasses: function (ds) { return getJson(endpoints.dsClasses(ds)); },

		/**
		 * @param {{class?:string[], ids?:string[], subclasses?:boolean, q?:string, related?:boolean, sort?:string, offset?:number, limit?:number}} p
		 *        ids: fetch exactly these objects (list view), e.g. rows that just became visible
		 * @returns {Promise<{seq, total, offset, limit, objects:[]}>}
		 */
		fetchObjects: function (ds, p) {
			return getJson(endpoints.dsObjects(ds, {
				'class': (p['class'] && p['class'].length) ? p['class'] : null,
				ids: (p.ids && p.ids.length) ? p.ids : null,
				subclasses: p.subclasses === false ? 'false' : null,
				q: p.q || null,
				related: p.related ? 'true' : null,
				sort: p.sort || null,
				offset: p.offset || 0,
				limit: p.limit || 200
			}));
		},

		/** @returns {Promise<{seq, object}>} */
		fetchObject: function (ds, iid, view) { return getJson(endpoints.dsObject(ds, iid, view)); },

		/** NEW (map): the positioned objects of a list filter, dead reckoned to the server's now. */
		/* extra: {events: <eventseq of the last answer, 0 the first time; null = no events>,
		          beams: true for radar and jammer beams, lasers: true for laser designators} */
		fetchMapObjects: function (ds, p, extra) {
			p = p || {};
			extra = extra || {};
			return getJson(endpoints.dsMap(ds, {
				events: typeof extra.events === 'number' ? String(extra.events) : null,
				beams: extra.beams ? 'true' : null,
				lasers: extra.lasers ? 'true' : null,
				'class': (p['class'] && p['class'].length) ? p['class'] : null,
				ids: (p.ids && p.ids.length) ? p.ids : null,
				subclasses: p.subclasses === false ? 'false' : null,
				q: p.q || null,
				related: p.related ? 'true' : null
			}), { timeoutMs: 15000 });
		},

		/** NEW (map): the background maps; {enabled:false} when the server has the map turned off. */
		fetchMaps: function () { return getJson(endpoints.maps()); },

		/** NEW (map overlays): {overlay, zoom, labels:[{t, lat, lon, k}], truncated} */
		fetchOverlayLabels: function (ov, bbox, zoom) { return getJson(endpoints.overlayLabels(ov, bbox, zoom), { timeoutMs: 15000 }); },

		/** @returns {Promise<{seq, more, interactions:[]}>} newest first */
		fetchInteractions: function (ds, p) {
			return getJson(endpoints.dsInteractions(ds, {
				limit: p.limit || 200,
				before: p.before || null,
				'class': (p['class'] && p['class'].length) ? p['class'] : null,
				object: p.object || null
			}));
		},

		/** @returns {Promise<{seq, interaction}>} with full parameter nodes */
		fetchInteraction: function (ds, id) { return getJson(endpoints.dsInteraction(ds, id)); },

		/** @returns {Promise<Object>} one running datasource with all details */
		fetchDatasource: function (id) {
			return getJson(endpoints.datasource(id)).then(function (data) {
				// Accept both {datasource:{...}} and a bare object.
				return (data && data.datasource && typeof data.datasource === 'object') ? data.datasource : data;
			});
		}
	};
})(window);
