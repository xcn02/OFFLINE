# OFFLINE Android V0.4.1 — légendes de la routine du lever

Deux champs de détails facultatifs ont été ajoutés aux réglages de la routine du matin. Chaque détail est affiché sous sa consigne sur l’accueil, avec retours à la ligne, et la case à cocher reste liée à la consigne. Les données V0.4 existantes sont conservées par le code grâce aux valeurs par défaut. Les détails ne sont pas repris dans la notification matinale pour éviter une notification trop longue.

# OFFLINE Android V0.4.0 — évolution ciblée depuis V0.3

## Modifications V0.4
- **Notification permanente de surveillance** : le service de pop-up n'est plus démarré en journée à l'ouverture des réglages. Son lancement est demandé à l'heure de déconnexion configurée. Il s'arrête à 04h, au lancement de la routine, après « Je vais dormir » ou « Ignorer ce soir ». Une ouverture d'OFFLINE dans la plage du soir peut aussi relancer le service. Le déclenchement depuis l'arrière-plan dépend des restrictions Android / batterie.
- **Rappels renforcés** : relance toutes les 5 minutes, canal de notification séparé de priorité haute avec vibration marquée, rappel à l'heure du coucher, pop-up au déverrouillage si autorisé. Android conserve le contrôle des sons, des alertes et des autorisations, donc l'affichage n'est pas garanti à chaque fois. Les niveaux Normal et Insistant restent disponibles.
- **Petite routine du lever** : option désactivée par défaut. Jusqu'à deux consignes libres dans Réglages > Petite routine du lever, affichées le matin dans Accueil avec cases à cocher enregistrées pour la journée. Le rappel à l'heure du réveil reprend les consignes et, si activées, les priorités de la veille.
- **V0.3 conservée** : interface, notes, historique, activités, mode avion, paramètres et données conservés par le code.

## Installation / signature
Workflow GitHub Actions : `gradle :app:assembleRelease` puis signature de test et export APK ARM64.
**Attention** : la clé de test est régénérée à chaque compilation. Installer une nouvelle version par-dessus une précédente signée différemment ne fonctionne pas et désinstaller l'ancienne fait perdre les données locales. Pour protéger les journaux et historiques, configurer une clé de signature permanente avant mise à jour.

## Limites Android
- Début de service en arrière-plan à heure donnée : limité par Android (AlarmManager inexact, démarrage de service au premier plan, restrictions batterie). La notification classique est la solution de secours.
- Pop-up au déverrouillage : nécessite la permission Android « Afficher par-dessus les autres applications », et peut ne pas fonctionner selon les restrictions du constructeur.
- Le mode avion reste une ouverture des réglages après « Je vais dormir », sans activation automatique.

## Tests recommandés
1. En journée : aucune notification persistante de surveillance, malgré pop-up activé.
2. Heure de déconnexion : notification de rappel ; si Android permet le service, notification persistante de surveillance.
3. « Je coupe » : arrêt des rappels et du service.
4. Mode renforcé : notification toutes les 5 min avant le début de la routine, vibration si autorisée.
5. Réglages : routine du lever activée avec deux consignes, visibles le matin dans Accueil ; cases conservées après fermeture et réouverture.
6. Le lendemain : cases du lever remises à zéro.

**Compilation non exécutée dans cet environnement** : SDK Android/Gradle non disponibles. Le workflow GitHub Actions est fourni, mais son résultat doit être testé.


## V0.4.2 — Organisation de la page Routine
- Routine du matin déplacée depuis Réglages vers la fin de l’onglet Routine.
- Séparation visuelle des activités du soir, de leur gestion et de la routine du matin.
- Bouton « Je vais dormir » immédiatement après les activités du soir.
- Données et paramètres existants conservés.
