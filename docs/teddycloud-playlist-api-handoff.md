# Auftrag: Gesamtdauer für die TeddyRemote-Kapitelliste bereitstellen

## Ergänzung: versionsgebundene native Metadaten

Für native V3-Inhalte verwendet TeddyRemote `playlist.kind = "native_collection"`
und ausschließlich eine zur angefragten `contentVersion` passende Playlist. Eine
fehlende oder abweichende Version liefert keine scheinbar passenden Kapitel aus
dem Figurenmodell. `chapterCount` bestimmt die Anzahl; fehlende Anzahl bedeutet
unbekannt. Leere Namen werden positionsgetreu als `Kapitel N` dargestellt. Die
optionale Intro-/Outro-Heuristik gilt nicht für native Playlists.

`playlist.durations` enthält Sekunden als JSON-Zahlen (auch Dezimalwerte) oder
`null`. Intern werden Millisekunden verwendet; unbekannte Dauern erscheinen als
`—`. Eine Gesamtzeit wird erst bei vollständig bekannten Dauern summiert und für
die Anzeige gekürzt. Die von der Box gemeldeten `chapterDuration` und
`chapterUntilMs` bleiben für die laufende Positionsrechnung vorrangig. TAF-Starts
und `totalSeconds` behalten die unten beschriebene Differenzrechnung.

Die optionalen Felder `metadataState`, `metadataReason` und `metadataRevision`
werden aus `playlist` übernommen. `runtime.playback.metadataRevision` ist eine
undurchsichtige Zeichenfolge und Bestandteil des lokalen Metadatenschlüssels
(Profil, Box, RUID, Version, Revision). Das bestehende Boxpolling erkennt damit
nachgelieferte Informationen derselben Version; es gibt keine zusätzliche
Cloudabfrage. Verspätete Antworten müssen weiterhin zur aktuellen Identität und
Revision passen, auch wenn MQTT währenddessen Kapitel/Position aktualisiert hat.

Inhaltstitel der bestätigten nativen Playlist haben Vorrang. Figurenname und
Figurenserie bleiben als separate Modelldaten erhalten. Ältere Backends ohne die
zusätzlichen Felder bleiben für TAF/TAP unterstützt; fehlende native Struktur
wird nicht aus Figuren-Kapiteln erfunden. Der Client löst durch Metadatenupdates
keine Wiedergabe-, Freshness- oder Sourcebefehle aus.

Gezielte Verifikation umfasst Resolver/API, Revisionsrennen, Dezimaldauern,
Gesamtsummen und bestehende Pause-/Seek-/Resume-Positionsrechnung. Ein erfolgreicher
JVM-Test/Kotlin-Build ersetzt keine Android-/Box-Hardwareabnahme.

Diese Anleitung ist für die LLM im **TeddyCloud-Projekt**. Im Android-Auftrag wurde kein TeddyCloud-Code verändert. Prüfe zuerst die Regeln und den tatsächlichen Stand deines eigenen Checkouts.

## Problem und Schnittstelle

`GET /api/getTagInfo?ruid=<RUID>&overlay=<BOX>&contentVersion=<VERSION>` liefert bei gecachten TAFs ohne explizite Source-Zuweisung Kapitelstarts in `tagInfo.trackSeconds`, aber keine Endzeit. Aus `[0,61,125]` kann ein Client nur die ersten beiden Dauern berechnen. Weder Dateigröße noch Kapitelname erlauben eine korrekte letzte Dauer.

Ergänze additiv **`tagInfo.totalSeconds`** als optionale, nicht negative ganzzahlige Gesamtdauer in Sekunden. Sie muss zum tatsächlich ausgewählten Content und genau derselben Zeitachse wie `trackSeconds` gehören. Kein String, keine Millisekunden und kein `0` als Platzhalter für unbekannt.

Beispiel:

```json
{"tagInfo":{"trackSeconds":[0,61,125],"totalSeconds":200}}
```

TeddyRemote 0.1.30 berechnet daraus `61`, `64`, `75` Sekunden. Explizite `playlist.durations` haben weiterhin Vorrang.

## Begrenzte Umsetzung

- Nutze die bereits berechnete Gesamtdauer der gültigen, vollständig vorhandenen TAF. Im untersuchten Stand existiert `additional.track_positions.total_seconds`; prüfe Herkunft, Gültigkeit und Auswahl im aktuellen Code.
- Keine Audiodatei allein für diesen API-Aufruf erneut herunterladen, encodieren oder vollständig scannen.
- Nur ausgeben, wenn die Gesamtdauer belastbar bekannt ist und zur ausgewählten TAF sowie den ausgegebenen Kapitelstarts gehört. Bei unbekannter Dauer, unvollständigem Content oder widersprüchlicher Zeitachse das Feld weglassen.
- Bei einer nativen Collection oder TAP niemals die Gesamtdauer eines zufällig daneben vorhandenen TAF-Caches verwenden. Vorhandene generationsgenaue `playlist.durations` bleiben unverändert.
- Keine Änderung an Content-Auswahl, Source-Zuweisung, Freshness, NoCloud, Streaming, gespeicherten Manifesten oder Client-Berechtigungen. Keine Vendor-/Cyclone-Änderungen.
- Bestehende Felder unverändert lassen; alte Clients ignorieren das neue Feld. Keine WebUI-Anpassung erforderlich.

## Gezielte Nachweise

1. Gecachte vollständige TAF ohne Kapitelnamen und ohne manuelle Source liefert Gesamtdauer einschließlich letztem Kapitel.
2. Ein-Kapitel-TAF: Start `0`, Gesamtdauer `75` ergibt `75` Sekunden.
3. Unbekannte oder inkonsistente Endzeit: Feld fehlt, keine erfundene Dauer.
4. TAF-/Native-/TAP-Wechsel: keine Vermischung von Zeitachsen oder Generationen.
5. Vorhandene `playlist.durations` und bisherige API-Antwortfelder bleiben kompatibel.

Dokumentiere die additive Schnittstelle bei den Playlist-Metadaten. Nur die betroffenen Contracts prüfen. Commit-, Build-, Deploy- und Push-Regeln des TeddyCloud-Projekts beachten; diese Anleitung erteilt keine zusätzliche Veröffentlichungsberechtigung.
