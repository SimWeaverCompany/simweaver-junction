/* ============================================================
   SimWeaver Rift - datasource configuration card.
   Shared by the overview (with a "Create datasource" action) and the
   datasource page (read-only snapshot in the collapsible config section).
   Usage: Rift.configCard.render(cfg, { actions: [Node, ...] })
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	/* Config fields rendered explicitly; any other field is shown generically,
	   so new (e.g. DIS) fields appear without code changes. */
	var HANDLED_CONFIG_KEYS = [
		'id', 'name', 'file', 'standard', 'standardlabel',
		'hlafederation', 'hlafederatetype', 'hlalsd', 'hlalsds', 'hladynamicfom',
		'hlareconnect', 'hlareconnectintervalsecs',
		'hlafomrootdir', 'hlafoms',
		'disinterface', 'disport', 'disentitytimeoutsecs', 'disexid',
		'origin', 'originlabel'
	];

	/** "HLA" / "DIS" / "" from a standard id such as "IEEE1516_2010". */
	function familyOf(standard) {
		var s = String(standard || '').toUpperCase();
		if (s.indexOf('IEEE1516') === 0 || s.indexOf('HLA') === 0) { return 'HLA'; }
		if (s.indexOf('IEEE1278') === 0 || s.indexOf('DIS') === 0 || s === 'CDIS') { return 'DIS'; }
		return '';
	}

	/**
	 * True for a DIS configuration: a DIS standard id, or (standard unknown) DIS settings
	 * without an HLA federation.
	 */
	function isDis(cfg) {
		if (!cfg) { return false; }
		var family = familyOf(cfg.standard);
		if (family) { return family === 'DIS'; }
		return ('disexid' in cfg) && (cfg.hlafederation === undefined || cfg.hlafederation === null || cfg.hlafederation === '');
	}

	/** "IEEE1516_2010" -> "IEEE 1516-2010", "IEEE1278_1_2012" -> "IEEE 1278.1-2012" */
	function standardCode(standard) {
		return String(standard || '')
			.replace(/^CDIS$/i, 'C-DIS')
			.replace(/^IEEE(\d+)_(\d+)_(\d{4})$/i, 'IEEE $1.$2-$3')
			.replace(/^IEEE(\d+)_(\d+)$/i, 'IEEE $1-$2');
	}

	/** "1", or "0 (all exercises)" */
	function exerciseText(v) {
		if (v === undefined || v === null || v === '') { return '—'; }
		return String(v) === '0' ? '0 (all exercises)' : String(v);
	}

	function reconnectNode(cfg) {
		if (cfg.hlareconnect === undefined) { return ui.valueNode(undefined); }
		var wrap = el('span', null, ui.valueNode(!!cfg.hlareconnect));
		if (cfg.hlareconnect && cfg.hlareconnectintervalsecs !== undefined) {
			wrap.appendChild(el('span.dim', { text: ' every ' + cfg.hlareconnectintervalsecs + ' s' }));
		}
		return wrap;
	}

	function fomListNode(foms) {
		if (!Array.isArray(foms) || foms.length === 0) { return ui.valueNode([]); }
		return el('ol.fom-list', null, foms.map(function (fom, i) {
			return el('li', null, [
				el('span.fom-idx', { text: (i < 9 ? '0' : '') + (i + 1) }),
				el('span.fom-name', { text: String(fom), title: String(fom) })
			]);
		}));
	}

	/**
	 * @param {Object} cfg  one config object (format of /api/datasourceconfigs)
	 * @param {{actions?: Node[], federation?: string}} [options]
	 *        federation: the federation actually joined (shown when it differs from the config)
	 */
	function render(cfg, options) {
		options = options || {};
		var family = familyOf(cfg.standard);
		var dis = isDis(cfg);
		var rows = [];

		if (dis) {
			var ex = el('span.hl', { text: exerciseText(cfg.disexid) });
			if (options.federation && String(options.federation) !== String(cfg.disexid)) {
				ex = el('span', null, [
					el('span.hl', { text: exerciseText(options.federation) }),
					el('span.dim', { text: ' (config: ' + exerciseText(cfg.disexid) + ')' })
				]);
			}
			rows.push({ label: 'EXERCISE', node: ex });
			rows.push({ label: 'INTERFACE', node: cfg.disinterface ? el('code.path', { text: String(cfg.disinterface) })
				: el('span.dim', { text: 'all interfaces' }) });
			if ('disport' in cfg) { rows.push({ label: 'UDP PORT', node: ui.valueNode(cfg.disport) }); }
			if ('disentitytimeoutsecs' in cfg) {
				rows.push({ label: 'ENTITY TIMEOUT', node: el('span', null, [ui.valueNode(cfg.disentitytimeoutsecs), el('span.dim', { text: ' s' })]) });
			}
		} else if ('hlafederation' in cfg) {
			var fed = el('span.hl', { text: String(cfg.hlafederation) });
			if (options.federation && options.federation !== cfg.hlafederation) {
				fed = el('span', null, [
					el('span.hl', { text: options.federation }),
					el('span.dim', { text: ' (config: ' + cfg.hlafederation + ')' })
				]);
			}
			rows.push({ label: 'FEDERATION', node: fed });
		}
		if (!dis) {
			if ('hlafederatetype' in cfg) { rows.push({ label: 'FEDERATE TYPE', node: ui.valueNode(cfg.hlafederatetype) }); }
			if ('hlalsd' in cfg) {
				var lo = Array.isArray(cfg.hlalsds) && cfg.hlalsds.length ? cfg.hlalsds : [cfg.hlalsd];
				var lbl = function (v) { return v ? v : '(RTI default)'; };
				var n = ui.valueNode(lbl(lo[0]) + (lo.length > 1 ? '  +' + (lo.length - 1) : ''));
				if (lo.length > 1) { n.title = 'Default ' + lbl(lo[0]) + '; also offered: ' + lo.slice(1).map(lbl).join(', '); }
				rows.push({ label: 'LSD', node: n });
			}
			if ('hlareconnect' in cfg) { rows.push({ label: 'RECONNECT', node: reconnectNode(cfg) }); }
		}
		if ('hlafomrootdir' in cfg) {
			rows.push({ label: 'FOM ROOT', node: el('code.path', { text: String(cfg.hlafomrootdir) }) });
		}
		if ('hlafoms' in cfg) {
			var n = Array.isArray(cfg.hlafoms) ? cfg.hlafoms.length : 0;
			rows.push({ label: (dis ? 'REFERENCE FOM · ' : 'FOM MODULES · ') + n, node: fomListNode(cfg.hlafoms), wide: true });
		}
		Object.keys(cfg).forEach(function (key) {
			if (HANDLED_CONFIG_KEYS.indexOf(key) === -1) {
				rows.push({ label: ui.prettyKey(key), node: ui.valueNode(cfg[key]) });
			}
		});

		// NEW: configurations made by another component (Junction endpoints)
		var origin = cfg.origin ? String(cfg.origin) : '';
		return el('article.card.cfg' + (family ? '.fam-' + family.toLowerCase() : '') + (origin ? '.origin-' + origin.replace(/[^a-z0-9-]/gi, '') : ''), {
			'data-config-id': cfg.id
		}, [
			el('header.card-head', null, [
				family ? el('span.family', { text: family }) : null,
				el('h3.card-name', { text: cfg.name || '(unnamed)', title: cfg.name || null }),
				origin ? el('span.origin-chip', { title: cfg.originlabel || origin, text: origin.toUpperCase() }) : null,
				cfg.standard ? el('span.std-chip', { text: standardCode(cfg.standard) }) : null
			]),
			cfg.standardlabel ? el('div.card-sub', { text: cfg.standardlabel + (origin === 'junction' ? ' · from a Junction gateway configuration' : '') }) : null,
			ui.kvList(rows),
			el('footer.card-foot', null, [
				el('span.file', { title: 'Settings file' }, [
					el('span.file-glyph', { 'aria-hidden': 'true' }),
					origin ? (cfg.originlabel || origin) : String(cfg.file || '—')
				]),
				cfg.id !== undefined ? el('span.cfg-id', { text: 'CFG ' + cfg.id }) : null
			]),
			(options.actions && options.actions.length) ? el('div.card-actions', null, options.actions) : null
		]);
	}

	Rift.configCard = { render: render, familyOf: familyOf, isDis: isDis, standardCode: standardCode };
})(window);
