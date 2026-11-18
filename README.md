# OFFLINE Android — V0.3.0

Application Android Kotlin / Jetpack Compose pour décrocher des écrans le soir. Données stockées **localement** dans SharedPreferences et JSON. Aucune connexion ou compte nécessaire.

## Evolutions V0.3

- **Rappels** : message/titre personnalisables ; normal, insistant (par défaut), renforcé ; relance à 5/10/15 min ; notification prioritaire et persistante avec actions « Je coupe », « Encore 10 min », « Ignorer ce soir ». Le rappel cesse au lancement de la routine ou à la fin de la soirée.
- **Dernière activité** : « Je termine dans 10 minutes » sur l'accueil, avec relance programmée. Les reports restent facultatifs.
- **Pop-up lors d'un déverrouillage** : option dans Réglages > Rappels. Necessite l'autorisation Android « Afficher par-dessus les autres applis ». Un service au premier plan affiche une notification technique persistante et écoute le déverrouillage. Le système Android peut arrêter/limiter ce service : fonctionnement **non garanti** sur tous les modèles. Le pop-up est désactivé tant que l'utilisateur n'autorise pas explicitement la superposition. Après activation des permissions, activer l'option correspondante. Ne pas activer si la notification permanente gêne.
- **Activités** : les cases à cocher sont désormais liées à la progression guidée. Cocher l'étape active fait avancer, décocher permet de revenir à cette étape. La sauvegarde persiste.
- **Notes** : « Notes du soir » dépliables dans Accueil et Routine, modifiables même si la routine n'est pas commencée. Pendant les activités « Journal rapide » et « Préparer le lendemain », le champ approprié est dans la carte principale et synchronisé.
- **Notes quotidiennes** : une fiche locale par soirée ; changement de soirée à 04h ; notes consultables/modifiables dans Historique, y compris si la routine n'a pas été commencée. Les priorités du matin reprennent le texte enregistré la veille à l'heure configurée.
- **Surveillance** : YouTube et Instagram ajoutés aux applications surveillées par défaut, avec X et Reddit. Surveillance par quota, pas de blocage, requiert l'accès aux statistiques Android.
- **Fonctions V0.2 conservées** : modification, ordre et durée des activités ; minuteur, progression, trois transitions ; historique, mode avion à la fin (« Je vais dormir ») **ouvre le panneau Android, sans activer directement le mode avion** ; ne pas déranger ; niveau de gris manuel.

## Compiler

Le workflow `.github/workflows/android.yml` compile un APK release ARM64 optimisé avec R8, puis le signe. Il utilise **une clé de test régénérée à chaque compilation** : pour installer un nouvel APK, il faudra désinstaller celui signé avec la clé précédente, perdant les données locales. Pour distribuer des mises à jour préservant les notes, configurez une clé de signature **permanente** avant vos premiers usages prolongés.

Pas de validation de compilation ou d'exécution effectuée dans cet environnement faute d'Android SDK. Vérifiez la compilation GitHub Actions puis le fonctionnement réel sur votre téléphone (Android 13 à 15, permissions de notifications, de superposition, veille et redémarrage).
