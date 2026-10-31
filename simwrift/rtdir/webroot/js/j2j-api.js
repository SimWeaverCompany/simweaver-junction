/* ============================================================
   SimWeaver J2J - REST client (/api/j2j/...), on Rift.api (0.10.0).
   The calls are described in doc/j2j-rest-api.md.
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var api = Rift.api;

	/** The API prefix Rift uses (same origin), derived from Rift.api's own endpoints. */
	var ROOT = api.endpoints.serverInfo().replace(/\/api\/serverinfo$/, '');
	var BASE = ROOT + '/api/j2j';

	function enc(s) { return encodeURIComponent(String(s)); }

	var endpoints = {
		/* GET -> {"state":"off|running|error","problems":[],"self","network","listen","counts","now"} */
		status:   function () { return BASE; },
		/* GET -> {"members":[{id,name,self,state,statemessage,address,addresses,via,path,hops,channels,rttms,...}]} */
		members:  function () { return BASE + '/members'; },
		/* GET -> {"member":{... plus attempts, classes, rtthistory, node, lasterror}} */
		member:   function (id) { return BASE + '/members/' + enc(id); },
		/* POST -> {"rttms","path":[{id,rttms}]}; 409 UNREACHABLE, 504 PING_TIMEOUT */
		ping:     function (id) { return BASE + '/members/' + enc(id) + '/ping'; },
		/* POST -> {"added","removed","changed","state","problems"}; 422 MEMBERS_INVALID */
		reload:   function () { return BASE + '/members/reload'; },
		/* GET -> {"sites":[],"nodes":[{id,name,site,state,via,rttms}],"links":[{a,b,state,channels,rttms,cost,ours,reason}]} */
		topology: function () { return BASE + '/topology'; },
		/* GET -> {"sessions":[{name,kind,model,members:[{node,gateway,instance,state,local}],since}]} */
		sessions: function () { return BASE + '/sessions'; },
		/* GET -> {"events":[{at,level,event,text,fields}]} newest first */
		events:   function (limit) { return BASE + '/events?limit=' + (limit || 100); },
		/* POST -> {"key","keyid","fingerprint","line"}: a key text to hand out, not installed */
		newKey:   function () { return BASE + '/key/new'; },
		/* SSE: j2j.status, j2j.members (every 2 s), j2j.topology (on change), j2j.event */
		live:     function () { return api.endpoints.events(['j2j']); }
	};

	Rift.j2j = {
		endpoints: endpoints,
		status:   function () { return api.getJson(endpoints.status(), { timeoutMs: 6000 }); },
		members:  function () { return api.getJson(endpoints.members(), { timeoutMs: 6000 }).then(function (d) { return (d && d.members) || []; }); },
		member:   function (id) { return api.getJson(endpoints.member(id), { timeoutMs: 6000 }).then(function (d) { return d && d.member; }); },
		ping:     function (id) { return api.postJson(endpoints.ping(id), {}, { timeoutMs: 40000 }); },
		reload:   function () { return api.postJson(endpoints.reload(), {}, { timeoutMs: 20000 }); },
		topology: function () { return api.getJson(endpoints.topology(), { timeoutMs: 6000 }); },
		sessions: function () { return api.getJson(endpoints.sessions(), { timeoutMs: 6000 }).then(function (d) { return (d && d.sessions) || []; }); },
		events:   function (limit) { return api.getJson(endpoints.events(limit), { timeoutMs: 6000 }).then(function (d) { return (d && d.events) || []; }); },
		newKey:   function () { return api.postJson(endpoints.newKey(), {}); },
		live:     function (handlers) { return api.subscribe(endpoints.live(), handlers); }
	};
})(window);
