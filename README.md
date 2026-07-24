# Mes frais — application Android

MVP Android natif pour classer des justificatifs par déplacement et suivre les
droits repas au jour le jour.

## Fonctionnalités

- création d'un déplacement avec nom, dates et plafond repas quotidien ;
- import d'une facture en PDF ou en image ;
- prise de photo depuis l'appareil photo ;
- OCR local des images et de la première page des PDF, avec montants
  sélectionnables en un toucher ;
- copie locale privée sous un nom générique, par exemple
  `2026-07-22_repas_a1b2c3d4.jpg` ;
- consultation des justificatifs par jour et par déplacement ;
- total des repas, montant encore disponible et signalement d'un dépassement ;
- calcul du montant remboursable et annotation rouge automatique du justificatif
  lorsqu'un repas dépasse le droit journalier restant ;
- catégories repas, transport, hôtel et autre ;
- suppression protégée par une confirmation.

Les données et fichiers restent uniquement dans l'espace privé de l'application.
La désinstallation de l'application les supprime.

## Lancer le projet

1. Installer Android Studio avec le SDK Android 36 et un JDK 17.
2. Ouvrir ce dossier comme projet Android.
3. Laisser Android Studio synchroniser Gradle.
4. Lancer la configuration `app` sur un téléphone Android 8.0+ ou un émulateur.

Le projet utilise Kotlin, Jetpack Compose et un stockage local sans serveur.

La chaîne de compilation en ligne de commande est également installée sur le
poste. Pour régénérer l'APK de test depuis PowerShell :

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

L'APK est produit dans `app\build\outputs\apk\debug\app-debug.apk`.

## Limites connues du MVP

- l'OCR analyse uniquement la première page des documents PDF ;
- pas de synchronisation cloud ni d'export groupé ;
- les dates sont saisies au format ISO `AAAA-MM-JJ`.
