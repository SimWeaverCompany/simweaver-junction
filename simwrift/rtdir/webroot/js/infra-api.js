/* ============================================================
   SimWeaver Infra - REST client (/api/infra/...), on Rift.api (0.9.20).
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var api = Rift.api;

	/** The API prefix Rift uses (same origin), derived from Rift.api's own endpoints. */
	var BASE = api.endpoints.serverInfo().replace(/\/api\/serverinfo$/, '') + '/api/infra';

	function enc(s) { return encodeURIComponent(String(s)); }

	var endpoints = {
		/* GET -> {"enabled","now","mom","pollseconds","dis6":[..],"dis7":[..],"cdis":[..],"hla3":[..],"hla4":[..],"counts":{}} */
		overview:     function () { return BASE; },
		/* GET -> {"now","items":[{full item} | {"key","missing":true}]} */
		items:        function (keys) { return BASE + '/items?keys=' + keys.map(enc).join(','); },
		/* POST -> 202: ask the MOM for fresh values now */
		refresh:      function (key) { return BASE + '/items/' + enc(key) + '/refresh'; },
		/* POST {"federate":"<handle hex>"} -> 202; 409 MOM_REQUEST_FAILED */
		declarations: function (key) { return BASE + '/items/' + enc(key) + '/declarations'; }
	};

	Rift.infra = {
		endpoints: endpoints,

		fetchOverview: function () { return api.getJson(endpoints.overview()); },

		fetchItems: function (keys) {
			return api.getJson(endpoints.items(keys)).then(function (d) {
				return (d && Array.isArray(d.items)) ? d.items : [];
			});
		},

		refresh: function (key) { return api.postJson(endpoints.refresh(key), {}); },

		requestDeclarations: function (key, federateHandle) {
			return api.postJson(endpoints.declarations(key), { federate: federateHandle }, { timeoutMs: 15000 });
		}
	};
})(window);
