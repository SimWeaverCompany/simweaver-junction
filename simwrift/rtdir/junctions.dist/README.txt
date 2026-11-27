junctions.dist - every sample gateway configuration, as delivered
=================================================================

This folder (in the program folder) holds all the sample gateway configurations of this version
of SimWeaver Junction, untouched: gateways (*.junction.settings), endpoints/ and routers/.

etc/junctions in the site folder holds the ones in use. A new site gets a selection of these
samples (from etc.dist/junctions, at the first start), and this folder has the rest too:

  hla3-hla3-rpr2-rpr3.junction.settings          RPR v2 <-> RPR v3, converted
  hla3-hla3-entitytype-filter.junction.settings  entity types rewritten by value rules
  hla3-hla3-class-filter.junction.settings       entities, fire and detonations only
  exercise-hla3-dis7.junction.settings           a group with group variables (1 of 2)
  exercise-comms-comms.junction.settings         the same group's HLA Comms part (2 of 2)
  comms-comms-lobby-only.junction.settings       two HLA Comms federations, the lobby only
  comms-comms-dis7-radio.junction.settings       two HLA Comms federations, two channels on DIS radio
  hla3-hla3-two-rtis.junction.settings           two RTI versions in one process
  hla3-hla3-rpr2-netn.junction.settings          RPR v2 objects into NETN, with UniqueId and Callsign (0.12.0)
  dis7-j2j.junction.settings                     a DIS 7 network shared through a J2J session (0.10.0)
  hla3-j2j.junction.settings                     an RPR v2 federation shared through a J2J session (0.10.0)
  dis7-kafka.junction.settings                   a DIS 7 network shared through an Apache Kafka topic (0.13.0)
  hla3-kafka.junction.settings                   an RPR v2 federation as JSON records in a Kafka topic (0.13.0)

and the kits only they use: endpoints/hla3-rpr2-own-rti, endpoints/hla4-fedpro-rpr2,
endpoints/j2j-pdu, endpoints/j2j-rpr2, endpoints/hla3-netn, endpoints/kafka,
endpoints/kafka-sasl-ssl, routers/comms-full and routers/hla3-netn.

The server never reads this folder. Only etc/junctions in the site folder is used. The RPR
v2 <-> v3 table is in the program folder's mappings/ (a copy in etc/junctions/mappings is used
instead, if there is one).

- To use a sample, copy it (and the kits it refers to, if they are missing) to the same place
  under etc/junctions. It appears in the gateway list without a restart.
- To see what you changed: diff -r <program>/junctions.dist <site>/etc/junctions
  (files that exist only here are samples not in use).
- To start over with a sample, copy it from here again.
- An update brings a new program folder with its own junctions.dist and leaves your
  etc/junctions alone. Comparing the old and the new junctions.dist shows what changed in the
  samples.
