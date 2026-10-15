# Auftrag: Gesamtdauer für die TeddyRemote-Kapitelliste bereitstellen

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
