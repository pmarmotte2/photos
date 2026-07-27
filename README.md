# Fo Notes — application Android

MVP Android natif pour classer des justificatifs par déplacement et suivre les
droits repas au jour le jour.

## Fonctionnalités

- création d'un déplacement avec nom, dates et plafond repas quotidien ;
- import d'une facture en PDF ou en image ;
- prise de photo depuis l'appareil photo ;
- recadrage libre de la photo autour de la facture avant l'OCR ;
- OCR local des images et de la première page des PDF, avec montants
  et dates détectés sélectionnables en un toucher ;
- copie locale privée sous un nom générique, par exemple
  `2026-07-22_repas_a1b2c3d4.jpg` ;
- consultation des justificatifs par jour et par déplacement ;
- modification de la date, du montant et du type ATOS en touchant l'icône
  du justificatif ;
- export d'un déplacement par e-mail depuis l'accueil ou son écran de détail,
  avec récapitulatif CSV et justificatifs renommés regroupés dans une archive ZIP ;
- suivi de la date de soumission et du statut de chaque note de frais :
  en cours de saisie, envoyée, validée ou remboursée ;
- message d'information dynamique récupéré au démarrage depuis le VPS ;
- total des repas, montant encore disponible et signalement d'un dépassement ;
- calcul du montant remboursable et annotation rouge automatique du justificatif
  lorsqu'un repas dépasse le droit journalier restant ;
- catalogue des types de frais ATOS regroupés par catégorie ;
- écran de paramétrage des plafonds par type de frais, avec réglages distincts
  pour Paris, la province et l'étranger ;
- recalcul automatique des justificatifs des notes encore en cours de saisie
  lors d'une modification des plafonds ;
- choix de la zone Paris/Sophia ou Province à la création d'un déplacement,
  avec plafonds repas automatiques et optimisation lunch + dinner ;
- modification de la zone et suppression complète d'une note de frais depuis
  l'écran d'accueil ;
- déduction quotidienne de la participation employeur au ticket restaurant,
  réglable dans les paramètres et fixée à 6 € par défaut ;
- conversion automatique des justificatifs image au format PDF lors de l'export ;
- application du plafond personnalisé le plus strict et annotation rouge
  automatique du montant remboursable ;
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

## Message dynamique

Le message affiché au lancement est lu depuis
`https://82.165.175.13/fo-notes-message.txt`.

Pour le modifier sans republier l'application, éditer sur le VPS :
`/var/www/fo-notes/fo-notes-message.txt`.
Un fichier vide désactive la popup.

## Télécharger l'APK

La dernière version de test est disponible directement dans le dépôt :
[Fo Notes 1.18.0](releases/Fo-Notes-1.18.0.apk).

## Extension Chrome de diagnostic

Le dossier [`chrome-extension-diagnostic`](chrome-extension-diagnostic) contient
une extension Manifest V3 qui relève les champs et sélecteurs techniques du
formulaire ATOS sans collecter les valeurs saisies. Une archive prête à
décompresser est disponible dans
[`releases/Fo-Notes-Diagnostic-0.1.0.zip`](releases/Fo-Notes-Diagnostic-0.1.0.zip).

## Extension Chrome de remplissage ATOS

Le dossier [`chrome-extension-filler`](chrome-extension-filler) contient
l’assistant de saisie ATOS. Il analyse le mail ou le CSV du ZIP Fo Notes,
prévisualise les lignes, renseigne les champs et ajoute les justificatifs sans
déclencher l’étape finale **Review/Send**.

L’archive installable est disponible dans
[`releases/Fo-Notes-ATOS-0.1.0.zip`](releases/Fo-Notes-ATOS-0.1.0.zip).

## Limites connues du MVP

- l'OCR analyse uniquement la première page des documents PDF ;
- pas de synchronisation cloud ni d'export groupé.
