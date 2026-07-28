# Fo Notes - Remplissage ATOS

Extension Chrome Manifest V3 qui prépare une note de frais dans l’écran
**Enter Receipts** du portail ATOS à partir d’un export Fo Notes.

## Fonctions

- analyse du mail récapitulatif collé ;
- lecture prioritaire de `recapitulatif.csv` lorsqu’un ZIP est sélectionné ;
- prévisualisation avant toute modification d’ATOS ;
- utilisation du montant remboursable lorsqu’il diffère du montant déclaré ;
- saisie du type, du montant, des trois dates et de la description ;
- regroupement des lignes `Lnch+Dnnr ... (cumulated)` d’une même journée ;
- temporisation prudente de 0,5 seconde entre les actions de saisie ;
- arrêt avant **Review/Send** afin de laisser une validation humaine.

La description ATOS est le commentaire saisi dans Fo Notes. Si celui-ci est
vide, l’extension utilise `libellé — date`.

## Installation locale

1. Décompresser l’archive de l’extension.
2. Ouvrir `chrome://extensions`.
3. Activer **Mode développeur**.
4. Cliquer sur **Charger l’extension non empaquetée**.
5. Sélectionner le dossier `chrome-extension-filler`.
6. Recharger la page ATOS.

## Utilisation

1. Ouvrir une note de frais ATOS à l’étape **Enter Receipts**.
2. Cliquer sur l’icône de l’extension ou sur le bouton flottant **FO**.
3. Coller le mail récapitulatif Fo Notes.
4. Sélectionner le ZIP reçu par mail.
5. Cliquer sur **Analyser l’export** et contrôler la prévisualisation.
6. Confirmer que les lignes ne sont pas déjà présentes.
7. Cliquer sur **Remplir ATOS**.
8. Contrôler le résultat avant d’utiliser **Review/Send**.

Les pièces jointes ne sont pas envoyées par l’extension. Le ZIP sert uniquement
à lire le fichier CSV de référence.

## Sécurité

- l’extension est limitée à `https://nextgen.myatos.net/*` ;
- aucune donnée n’est envoyée vers un serveur externe ;
- le mail et le ZIP restent traités localement dans Chrome ;
- l’extension ne clique jamais sur **Review/Send** ;
- en cas d’erreur, elle s’arrête et conserve les lignes déjà acceptées pour
  permettre un contrôle manuel.

## Tests

```powershell
node --test chrome-extension-filler/tests/*.test.js
```

L’automatisation dépend de la structure actuelle du formulaire ATOS observée
le 27 juillet 2026. Une évolution du portail peut nécessiter d’actualiser les
sélecteurs.
