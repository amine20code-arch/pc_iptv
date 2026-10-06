# Stream TV 2.1 (version plate : tous les fichiers dans un seul dossier)

1. Compilation de l'APK sur GitHub : collez le contenu de build-apk.yml dans .github/workflows/build-apk.yml, puis Actions > Run workflow.
2. Chaque compilation publie une Release avec l'APK : l'application peut se mettre à jour toute seule (Réglages > Mises à jour).
3. La clé de signature (streamtv.p12) est incluse : toutes les versions ont la même signature, donc les mises à jour s'installent par-dessus.
Lecteurs : ExoPlayer (Media3) + VLC (libVLC). Si la compilation échoue sur "libvlc-all:3.6.0", utilisez 3.5.1 dans build.gradle.kts.
