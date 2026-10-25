/* ============================================================
   SimWeaver Rift - dark / light theme switch.
   - Dark ("night ops") is the default, light is "desert".
   - The choice is remembered in the browser (localStorage), so it
     follows you between all pages of the application.
   - ?theme=light or ?theme=dark in the URL overrides it.
   Load this in <head> (not at the end) so the page never flashes
   in the wrong theme. The toggle button needs id="btn-theme".
   ============================================================ */

(function (window, document) {
	'use strict';

	var THEME_KEY = 'simweaver-rift-theme';

	function apply(mode) {
		var theme = (mode === 'light') ? 'light' : 'dark';
		document.documentElement.classList.toggle('light', theme === 'light');
		try { window.localStorage.setItem(THEME_KEY, theme); } catch (e) { /* storage blocked - ignore */ }

		var btn = document.getElementById('btn-theme');
		if (btn) {
			btn.textContent = (theme === 'light') ? '☾ Night' : '☀ Day';
			btn.setAttribute('aria-pressed', theme === 'light' ? 'true' : 'false');
		}
	}

	function toggle() {
		apply(document.documentElement.classList.contains('light') ? 'dark' : 'light');
	}

	// Starting theme: URL parameter, then remembered choice, then dark.
	var initial = 'dark';
	try {
		var saved = window.localStorage.getItem(THEME_KEY);
		if (saved === 'light' || saved === 'dark') { initial = saved; }
		var q = new URLSearchParams(window.location.search).get('theme');
		if (q === 'light' || q === 'dark') { initial = q; }
	} catch (e) { /* keep default */ }

	apply(initial);   // sets the class right away (no flash)
	document.addEventListener('DOMContentLoaded', function () {
		apply(initial);   // ...and the button label once it exists
		var btn = document.getElementById('btn-theme');
		if (btn) { btn.addEventListener('click', toggle); }
	});

	window.Rift = window.Rift || {};
	window.Rift.theme = { set: apply, toggle: toggle };
})(window, document);
