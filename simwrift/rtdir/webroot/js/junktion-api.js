/* ============================================================
   SimWeaver Junktion - REST client (/api/junktion/...), on Rift.api.
   All Junktion endpoints are declared here.
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var api = Rift.api;

	/** The API prefix Rift uses (same origin), derived from Rift.api's own endpoints. */
	var BASE = api.endpoints.serverInfo().replace(/\/api\/serverinfo$/, '') + '/api/junktion';

	function enc(s) { return encodeURIComponent(String(s)); }

	var endpoints = {
		/* GET  -> {"gateways":[config...],"endpointkits":[...],"routerkits":[...],"problems":[...],"directory":".."} */
		configs:        function () { return BASE + '/configs'; },
		/* GET  -> {"config":"..","routes":[{"from","to","conversion","report":{...}}],"errors":{}} */
		compatibility:  function (cfgId) { return BASE + '/configs/' + enc(cfgId) + '/compatibility'; },
		/* GET  -> {"endpoint","default","result":{"kind":"federation|exercise|channel","targets":[...],"error"?}} */
		targets:        function (cfgId, epId, federation) {
			return BASE + '/configs/' + enc(cfgId) + '/endpoints/' + enc(epId) + '/targets'
				+ (federation ? '?federation=' + enc(federation) : '');
		},
		/* GET -> {"groups":[{"group","gateways":[ids],"variables":[{"name","default"?}],"running"}]} */
		groups:         function () { return BASE + '/groups'; },
		/* POST {"vars":{}} -> 201 all / 207 some {"success","allstarted","group","started","total","results":[{"configid","success","gateway"?,"error"?}]}; 400 GROUP_START_FAILED */
		groupStart:     function (g) { return BASE + '/groups/' + enc(g) + '/start'; },
		/* POST -> {"group","stopped"} */
		groupStop:      function (g) { return BASE + '/groups/' + enc(g) + '/stop'; },
		/* GET -> {"gateways":[...]};  POST {"configid","targets":{},"vars":{}} -> 201 {"success","gateway","warning"?} */
		gateways:       function () { return BASE + '/gateways'; },
		/* GET -> {"gateway":{...}};  DELETE -> 204 */
		gateway:        function (gid) { return BASE + '/gateways/' + enc(gid); },
		/* GET -> {"routes":[...]} */
		gatewayCompat:  function (gid) { return BASE + '/gateways/' + enc(gid) + '/compatibility'; },
		/* POST -> 201/200 {"success","inspect":{"datasource","page","created"}} */
		inspect:        function (gid, epId) { return BASE + '/gateways/' + enc(gid) + '/endpoints/' + enc(epId) + '/inspect'; },
		/* SSE: topic "junktion" (event "gateways"), "junktion:<gid>" (events "gateway", "gateway.removed") */
		events:         function (topics) { return api.endpoints.events(topics); }
	};

	Rift.junktion = {
		endpoints: endpoints,

		fetchConfigs: function () { return api.getJson(endpoints.configs()); },

		fetchCompatibility: function (cfgId) { return api.getJson(endpoints.compatibility(cfgId), { timeoutMs: 60000 }); },

		fetchGatewayCompatibility: function (gid) { return api.getJson(endpoints.gatewayCompat(gid)); },

		/** @returns {Promise<{kind, targets, error?, default}>} */
		fetchTargets: function (cfgId, epId, federation) {
			return api.getJson(endpoints.targets(cfgId, epId, federation), { timeoutMs: 20000 }).then(function (d) {
				var r = (d && d.result) || {};
				return { kind: r.kind || '', targets: Array.isArray(r.targets) ? r.targets : [], error: r.error || null,
					'default': d ? d['default'] : '' };
			});
		},

		fetchGateways: function () {
			return api.getJson(endpoints.gateways()).then(function (d) {
				return (d && Array.isArray(d.gateways)) ? d.gateways : [];
			});
		},

		/** Starts a gateway; joins can take a while. @returns {Promise<{gateway, warning?}>} */
		startGateway: function (cfgId, targets, vars) {
			return api.postJson(endpoints.gateways(), { configid: cfgId, targets: targets || {}, vars: vars || {} }, { timeoutMs: 120000 });
		},

		/** Starts every gateway of a group. Partial success (207) resolves: check results[].success. */
		startGroup: function (group, vars) {
			return api.postJson(endpoints.groupStart(group), { vars: vars || {} }, { timeoutMs: 300000 });
		},

		stopGroup: function (group) {
			return api.postJson(endpoints.groupStop(group), {}, { timeoutMs: 120000 });
		},

		stopGateway: function (gid) {
			return api.request('DELETE', endpoints.gateway(gid), undefined, { timeoutMs: 60000 });
		},

		/** @returns {Promise<{datasource, page, created}>} */
		inspect: function (gid, epId) {
			return api.postJson(endpoints.inspect(gid, epId), {}, { timeoutMs: 60000 }).then(function (d) {
				return (d && d.inspect) || {};
			});
		}
	};
})(window);
