# Fo Notes — extension de diagnostic ATOS

Cette extension Chrome Manifest V3 relève la structure technique de la page
`Enter Receipts` afin de préparer l’automatisation de la saisie.

Elle ne collecte pas :

- les valeurs présentes dans les champs ;
- les noms ou autres textes généraux de la page ;
- les identifiants de connexion ;
- les cookies ;
- les pièces jointes.

Le JSON contient uniquement les champs, boutons, listes, libellés, en-têtes,
attributs techniques SAP, sélecteurs DOM et informations d’iframes.

## Installation

1. Ouvrir `chrome://extensions`.
2. Activer **Mode développeur**.
3. Cliquer sur **Charger l’extension non empaquetée**.
4. Sélectionner le dossier `chrome-extension-diagnostic`.
5. Recharger l’onglet `nextgen.myatos.net`.

## Création du diagnostic

1. Ouvrir une note de frais sur l’étape **Enter Receipts**.
2. Déplier une ligne afin que tous les champs détaillés soient visibles.
3. Ouvrir l’extension **Fo Notes - Diagnostic ATOS**.
4. Cliquer sur **Analyser la page**.
5. Utiliser **Surligner** pour vérifier visuellement la détection.
6. Télécharger le fichier JSON et transmettre uniquement ce fichier.

Pour obtenir les sélecteurs de la fenêtre d’ajout des justificatifs, refaire
une analyse après avoir ouvert **Attach Receipts**.
