/* ============================================================
   SimWeaver Rift - "Remove datasource" flow, shared by the
   overview page and the datasource detail page.
   Usage: Rift.removeDatasource.confirm({id, name}, onRemoved, options)
          onRemoved(outcome) with outcome = {removed, alreadyGone, name}
          options.toast = false: no toast here (caller shows it, e.g. after a redirect)
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;

	function displayName(ds) {
		return (ds && ds.name) ? String(ds.name) : 'datasource ' + (ds ? ds.id : '?');
	}

	function confirm(ds, onRemoved, options) {
		var showToast = !(options && options.toast === false);
		var name = displayName(ds);
		Rift.confirmDialog.open({
			title: 'Remove datasource',
			tag: 'DEL',
			subject: name,
			message: 'The datasource will resign from its federation, disconnect from the RTI ' +
				'and be removed from the list of running datasources. This cannot be undone.',
			confirmLabel: '✕ Disconnect & remove',
			busyLabel: 'Disconnecting…',
			failTitle: 'Datasource was not removed',
			action: function () { return Rift.api.removeDatasource(ds.id); },
			onDone: function (outcome) {
				if (showToast) {
					Rift.ui.toast(outcome.alreadyGone
						? 'Datasource ' + name + ' was already gone.'
						: 'Datasource ' + name + ' disconnected and removed.', outcome.alreadyGone ? 'info' : 'ok');
				}
				if (onRemoved) {
					onRemoved({ removed: outcome.removed, alreadyGone: outcome.alreadyGone, name: name });
				}
			}
		});
	}

	Rift.removeDatasource = { confirm: confirm, displayName: displayName };
})(window);
