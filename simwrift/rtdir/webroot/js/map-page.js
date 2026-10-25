/* ============================================================
   SimWeaver Rift - the map page (map.html?id=<datasource>), NEW.
   The datasource page's "Map tab": a full-size map with the list of
   the entities on it. The filter comes from the URL, as the object
   list had it:
     map.html?id=..&class=a,b&subclasses=false&q=text&related=true
             &goto=<object id>&follow=<object id>&map=<background map id>
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var params = new URLSearchParams(window.location.search);
	var dsid = params.get('id');
	var filter = {
		'class': (params.get('class') || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean),
		subclasses: params.get('subclasses') !== 'false',
		q: params.get('q') || '',
		related: params.get('related') === 'true'
	};
	var FORCE = { friendly: '#2f80ed', opposing: '#e5484d', neutral: '#2fa36b', other: '#f0b429' };
	var mv = null, signature = '', selectedId = null, objects = [];

	function $(id) { return document.getElementById(id); }

	function simple(q) { return String(q).split('.').pop(); }

	function describeFilter() {
		var parts = [];
		if (filter['class'].length) { parts.push('classes ' + filter['class'].map(simple).join(', ') + (filter.subclasses ? ' (and subclasses)' : '')); }
		if (filter.q) { parts.push('search "' + filter.q + '"'); }
		if (filter.related) { parts.push('+ related'); }
		var box = $('mp-filter');
		ui.clear(box);
		box.appendChild(document.createTextNode(parts.length ? 'Filter: ' + parts.join(', ') : 'All objects with a position'));
	}

	function renderList(force) {
		var q = $('mp-search').value.trim().toLowerCase();
		var rows = objects.slice().sort(function (a, b) {
			return String(a.label || a.name).localeCompare(String(b.label || b.name));
		});
		var sig = q + '|' + rows.map(function (o) {
			return o.id + ':' + (o.label || '') + ':' + o.force + ':' + (o.children || []).map(function (k) { return k.id; }).join(',');
		}).join(';');
		if (!force && sig === signature) { markSelected(); return; }
		signature = sig;
		var list = $('mp-list');
		var top = list.scrollTop;
		ui.clear(list);
		var hit = function (o) {
			return !q || [o.label, o.name, o.entityid, o['class']].filter(Boolean).join(' ').toLowerCase().indexOf(q) >= 0;
		};
		var n = 0;
		rows.forEach(function (o) {
			var kids = (o.children || []).filter(hit);
			if (!hit(o) && !kids.length) { return; }
			n++;
			list.appendChild(row(o, false));
			(q ? kids : o.children || []).forEach(function (k) { list.appendChild(row(k, true, o)); });
		});
		if (!n) { list.appendChild(el('li', null, el('div.mp-filter', { style: 'padding:10px 12px', text: q ? 'Nothing matches.' : 'No entities with a position yet.' }))); }
		list.scrollTop = top;
		markSelected();
	}

	function row(o, kid, parent) {
		var colour = FORCE[(parent || o).force] || '#9aa3ad';
		return el('li' + (kid ? '.kid' : ''), { 'data-id': o.id }, el('button', {
			type: 'button', title: kid ? 'Carried by ' + (parent.label || parent.name) + ' - click to go to it' : 'Click: go to it. Double-click: follow it.',
			on: {
				click: function () { mv.goTo(o.id); },
				dblclick: function () { mv.goTo(o.id, { follow: true }); }
			}
		}, [
			kid ? el('span', { text: '└', style: 'color:var(--fg-faint)' }) : el('i.mv-dot', { style: 'background:' + colour + (o.context ? ';opacity:0.45' : '') }),
			el('span.k', { text: o.label || o.name }),
			el('span.c', { text: [o.entityid, o['class'], o.context ? 'outside the filter' : null].filter(Boolean).join(' · ') }),
			!kid && (o.children || []).length ? el('span.n', { text: '+' + o.children.length }) : el('span')
		]));
	}

	function markSelected() {
		Array.prototype.forEach.call($('mp-list').children, function (li) {
			li.classList.toggle('on', !!selectedId && li.getAttribute('data-id') === selectedId);
		});
	}

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		if (!dsid) {
			$('mp-ds').textContent = 'No datasource';
			$('mp-foot').textContent = 'Open the map from a datasource page (Map tab).';
			return;
		}
		describeFilter();
		var back = el('a', { href: 'datasource.html?id=' + encodeURIComponent(dsid), title: 'Back to the datasource page' }, '‹ …');
		ui.clear($('mp-ds')).appendChild(back);
		Rift.api.fetchDatasource(dsid).then(function (d) {
			var name = (d && (d.name || (d.datasource && d.datasource.name))) || dsid;
			back.textContent = '‹ ' + name;
			document.title = name + ' · Map · SimWeaver Rift';
		}, function () { back.textContent = '‹ datasource ' + dsid.slice(0, 8) + '…'; });

		mv = new Rift.MapView($('mp-map'), {
			dsid: dsid,
			filter: filter,
			storageKey: 'rift-map:' + dsid,
			map: params.get('map') || null,
			onMapChange: function (id, isDefault) {
				// keep the address in step, so a copied link shows the same map
				var p = new URLSearchParams(window.location.search);
				if (id && !isDefault) { p.set('map', id); } else { p['delete']('map'); }
				history.replaceState(null, '', '?' + p.toString() + window.location.hash);
			},
			onOpenObject: function (t) {
				window.open('datasource.html?id=' + encodeURIComponent(dsid) + '&focus=' + encodeURIComponent(t.instanceid), '_blank', 'noopener');
			},
			onData: function (list, info) {
				objects = list;
				renderList(false);
				var parts = [info.shown + ' on the map'];
				if (info.children) { parts.push(info.children + ' carried'); }
				if (info.unpositioned) { parts.push(info.unpositioned + ' without position'); }
				$('mp-foot').textContent = parts.join(' · ');
			},
			onSelect: function (sel) {
				selectedId = sel ? sel.id : null;
				markSelected();
			}
		});
		$('mp-search').addEventListener('input', function () { renderList(true); });
		mv.start();
		var go = params.get('follow') || params.get('goto');
		if (go) { mv.goTo(go, { follow: !!params.get('follow') }); }
	});
})(window, document);
