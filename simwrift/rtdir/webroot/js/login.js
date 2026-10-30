/* ============================================================
   SimWeaver - the login page (0.11.0).
   POST /api/auth/session {"token"} signs this browser in: the server
   answers with a session cookie the page's scripts cannot read, and
   the browser sends it with every /api request, the live streams and
   map tiles included. Then back to the page that sent us here
   (?next=), or the Rift overview.
   ============================================================ */

(function (window, document) {
	'use strict';

	var api = window.Rift.api;
	var form = document.getElementById('login-form');
	var input = document.getElementById('login-token');
	var show = document.getElementById('login-show');
	var msg = document.getElementById('login-msg');
	var go = document.getElementById('login-go');

	/** Only a page of this server: a path starting with one "/", or a plain page name. */
	function nextPage() {
		var next = null;
		try { next = new URLSearchParams(window.location.search).get('next'); } catch (e) { /* none */ }
		if (next && /^\/(?![\/\\])/.test(next) && !/login\.html/.test(next)) { return next; }
		if (next && /^[a-z0-9-]+\.html([?#].*)?$/i.test(next) && !/^login\.html/i.test(next)) { return next; }
		return 'index.html';
	}

	function say(text) {
		msg.textContent = text;
		msg.hidden = !text;
	}

	function busy(on) {
		go.disabled = on;
		go.classList.toggle('busy', on);
		input.disabled = on;
	}

	// Already signed in, or authentication is off on this server: nothing to do here.
	api.getJson(api.endpoints.authWhoami(), { timeoutMs: 5000 }).then(function (reply) {
		var a = reply && reply.auth;
		if (a && (!a.enabled || a.name)) { window.location.replace(nextPage()); }
	}, function (err) {
		if (err && err.status === 0) { say('The server cannot be reached.'); }
	});

	show.addEventListener('change', function () {
		input.type = show.checked ? 'text' : 'password';
		input.focus();
	});

	input.addEventListener('input', function () {
		input.classList.remove('invalid');
		say('');
	});

	form.addEventListener('submit', function (ev) {
		ev.preventDefault();
		var token = input.value.trim();
		if (!token) {
			input.classList.add('invalid');
			say('Paste your API token first.');
			input.focus();
			return;
		}
		busy(true);
		say('');
		api.postJson(api.endpoints.authSession(), { token: token }, { timeoutMs: 10000 }).then(function () {
			input.value = '';
			window.location.replace(nextPage());
		}, function (err) {
			busy(false);
			input.classList.add('invalid');
			if (err && err.status === 401) {
				say('That token is not valid on this server. Check that you copied all of it.');
			} else if (err && err.status === 429) {
				say('Too many wrong tokens from this computer. Wait a minute and try again.');
			} else if (err && err.status === 409) {
				window.location.replace(nextPage());          // authentication was turned off meanwhile
				return;
			} else {
				say((err && err.message) || 'Signing in failed.');
			}
			input.focus();
			input.select();
		});
	});

	input.focus();
})(window, document);
