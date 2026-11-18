# OFFLINE — application Android de fin de soirée

Prototype natif Android (Kotlin + Jetpack Compose). Version **0.1.0**, sans compte ni serveur.

## Fonctionnalités actuellement codées

- Accueil : objectif de coucher, **Je coupe** et **Je vais dormir**.
- Routine : activités personnalisées (ajouter, renommer, modifier durée, rendre facultative, désactiver, supprimer, réordonner), cases à cocher pour chaque soirée.
- Notes du soir : priorités du lendemain et journal libre.
- Historique local : heure déclarée de déconnexion, heure déclarée de coucher et cause facultative des dérapages.
- Paramètres : heures de début, coucher et réveil, jours actifs, notifications, options système.
- Programmation Android : alarmes de rappel inexactes, reconstruites après redémarrage et changement d'heure.
- Option « Ne pas déranger » : nécessite l'autorisation système ; restauration du filtre précédent à l'heure du matin.
- Surveillance **intermédiaire** des applications via Usage Access : avertissement à partir du quota configuré, contrôle environ toutes les 15 minutes pendant la plage de routine. Les identifiants des applications sont saisis manuellement.
- Propositions pour noir et blanc et mode avion, avec liens directs vers les réglages Android.

## Limitations connues

- Le projet **n'a pas été compilé ni testé sur un appareil** dans cet environnement (SDK Android et Gradle indisponibles). Une vérification de compilation dans Android Studio est indispensable avant installation.
- Aucun APK précompilé n'est fourni.
- Le noir et blanc n'est **pas activé automatiquement** ; Android n'offre pas d'API publique standard pour le faire sans intervention/permissions privilégiées. Utiliser les réglages d'accessibilité ou le mode coucher du téléphone.
- Le mode avion ne peut pas être activé par une application classique : ouverture des paramètres seulement.
- Le PC ne peut pas être éteint depuis Android : seul un rappel est prévu.
- Android peut retarder les alarmes inexactes, surtout en économie de batterie.
- Le contrôle des distractions est un **avertissement**, pas un blocage des applications. La durée surveillée est indicative, selon les événements d'utilisation fournis par Android.
- Le journal et les notes sont stockés localement ; aucune sauvegarde distante.
- Les horaires traversant minuit et les cas de décalage horaire nécessitent encore des essais sur téléphone.

## Ouvrir et compiler

1. Installer Android Studio récent (SDK Android 35, JDK 17 ou 21).
2. Extraire le dossier `OFFLINE`, puis ouvrir **le dossier racine** dans Android Studio.
3. Ce ZIP contient le projet Gradle et sa configuration, mais **pas le JAR du Gradle Wrapper**, absent de l'environnement de génération. Installer Gradle 8.9 si nécessaire, puis dans le dossier racine exécuter `gradle wrapper --gradle-version 8.9` pour générer `gradlew`, `gradlew.bat` et `gradle-wrapper.jar`.
4. Synchroniser Gradle (accès Internet nécessaire au premier téléchargement des dépendances).
5. Lancer sur un téléphone Android 8.0+ ou faire **Build > Build APK(s)**.
6. Au premier lancement, autoriser les notifications. Pour les options choisies, autoriser l'accès aux statistiques d'utilisation et/ou « Ne pas déranger » depuis les paramètres de l'application.

## Architecture

- `MainActivity.kt` : interface Jetpack Compose ; quatre onglets.
- `Store.kt` : configuration, activités et historique en SharedPreferences/JSON.
- `Automation.kt` : programmation via `AlarmManager`, notifications, accès statistiques, rétablissement DND, événements de redémarrage.

## Prochaines étapes avant une version stable

- Tester les builds Android Studio et corriger toute incompatibilité dépendante de la version du SDK.
- Tester les alertes sur plusieurs marques et versions Android.
- Améliorer la saisie des applications surveillées avec un vrai sélecteur d'applications, plutôt que des noms de packages.
- Ajouter des notifications d'étapes optionnelles, un minuteur pour la lecture et des statistiques hebdomadaires.
- Offrir une exportation de données si nécessaire.
