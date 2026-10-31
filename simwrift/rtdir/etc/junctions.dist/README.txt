junctions.dist - every sample gateway configuration, as delivered
=================================================================

This folder holds all the sample gateway configurations of this version of SimWeaver Junction,
untouched: gateways (*.junction.settings), endpoints/, routers/ and mappings/.

etc/junctions holds the ones in use. It is delivered with a selection of these samples, and
this folder has the rest too:

  hla3-hla3-rpr2-rpr3.junction.settings          RPR v2 <-> RPR v3, converted
  hla3-hla3-entitytype-filter.junction.settings  entity types rewritten by value rules
  hla3-hla3-class-filter.junction.settings       entities, fire and detonations only
  exercise-hla3-dis7.junction.settings           a group with group variables (1 of 2)
  exercise-talk-talk.junction.settings           the same group's Talk part (2 of 2)
  talk-talk-lobby-only.junction.settings         two Talk federations, the lobby only
  talk-talk-dis7-radio.junction.settings         two Talk federations, two channels on DIS radio
  hla3-hla3-two-rtis.junction.settings           two RTI versions in one process
  dis7-j2j.junction.settings                     a DIS 7 network shared through a J2J session (0.10.0)
  hla3-j2j.junction.settings                     an RPR v2 federation shared through a J2J session (0.10.0)

and the kits only they use: endpoints/hla3-rpr2-own-rti, endpoints/hla4-fedpro-rpr2,
endpoints/j2j-pdu, endpoints/j2j-rpr2 and routers/talk-full.

The server never reads this folder. Only etc/junctions is used.

- To use a sample, copy it (and the kits it refers to, if they are missing) to the same place
  under etc/junctions. It appears in the gateway list without a restart.
- To see what you changed: diff -r etc/junctions.dist etc/junctions
  (files that exist only here are samples not in use).
- To start over with a sample, copy it from here again.
- When upgrading, replace this folder with the new version's, and keep your etc/junctions.
  Comparing the old and the new junctions.dist shows what changed in the samples.
