# OFFLINE Android — V0.2.0

Application personnelle Android Kotlin / Jetpack Compose pour la fin de soirée.

## Nouveautés V0.2

- Icône vectorielle OFFLINE (lune et étoiles).
- Routine guidée : appuyer sur « Je coupe » démarre la première activité ; barre de progression discrète, boutons pause/reprise et suivant ; état sauvegardé localement.
- Transitions paramétrables : hybride (automatique pour les activités chronométrées et manuel pour préparation/journal), automatique, manuel. Les activités de saisie sont actuellement reconnues par leurs IDs d'origine (3 et 4), y compris après renommage.
- « Je vais dormir » termine la soirée, même si certaines activités ne sont pas faites. Si l'option est cochée, ouvre la page Android du mode avion pour activation manuelle ; confirmation facultative.
- Journal et priorités enregistrés localement. Les priorités de la veille sont visibles dans l'accueil le lendemain et peuvent figurer dans un rappel matinal.
- Option d'aide au noir et blanc : explication et raccourci vers les réglages d'accessibilité. Android standard ne permet pas d'activer arbitrairement les niveaux de gris depuis une application ordinaire.
- Suppression du réglage redondant « rappel fin du PC » ; la notification de déconnexion reste.
- Release optimisée par R8 et shrinkResources ; `abiFilters = arm64-v8a`. Les dépendances Compose sont pour l'essentiel du bytecode Java/Kotlin : l'ABI ARM64 seule ne garantit pas une grosse diminution de taille.
- Version `0.2.0`, `versionCode = 2`, même `applicationId` : `fr.offline.soir`.

## Compiler

À la racine du projet, utiliser `gradle :app:assembleRelease --no-daemon --console=plain` avec Java 17, Gradle 8.9 et Android SDK 35 ; ou utiliser GitHub Actions inclus.

Le workflow GitHub génère et signe un APK Release ARM64 avec **une clé de test temporaire nouvelle à chaque build**. Cela autorise l'installation locale, mais **empêche une mise à jour par-dessus une version précédente signée avec une clé différente** : désinstaller l'ancienne APK avant d'installer la nouvelle. **L'ancienne application et ses données seront supprimées lors de cette désinstallation.** Pour les mises à jour sans perte de données, configurer une clé de signature permanente via GitHub Secrets.

## Limites actuelles

- Compilation non testée dans cet environnement (pas de Gradle/SDK Android). Le workflow GitHub doit confirmer le build.
- Le chronomètre utilise l'heure réelle et est sauvegardé ; il se met à jour tant que l'écran de l'app reste actif et retrouve le temps écoulé quand on rouvre l'application. Le passage automatique en arrière-plan n'est pas garanti sans service/notification persistante.
- Le noir et blanc n'est pas automatiquement activé ; configurer le mode coucher natif Android pour cela. L'option dans OFFLINE est une aide à la configuration.
- Le mode avion ne peut pas être activé par une app Android standard. OFFLINE ouvre la page système après l'appui sur « Je vais dormir ».
- Le quota de distractions fournit un avertissement indicatif, pas un blocage.
- Le suivi des notes reste dans OFFLINE, aucun envoi serveur.
