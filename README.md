<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHChatGames

*Mini-jeux de chat automatiques (mot à taper, mot à l'envers, calcul, suite, clic) avec récompenses.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Mini-jeux de chat automatiques (mot à taper, mot à l'envers, calcul, suite, clic) avec récompenses.

Utilisé sur : **Opale**.

Avec `SHNetwork`, un seul jeu tourne pour tous les serveurs Opale (voir « Jeu unique pour tout le réseau »). Sans lui, le plugin se comporte comme sur un serveur seul : chaque serveur lance ses jeux et compte les victoires dans `wins.yml`.

## Dépendances

- Obligatoires : `PlaceholderAPI`
- Optionnelles : `SHLogs`, `SHNetwork`

## Logs

Chaque gagnant récompensé est écrit dans le fichier `gagnants` de SHLogs, avec le nom du jeu et la récompense reçue. Le texte de la ligne se règle avec `win_log_format`, le nom des jeux avec `log_name` et celui des récompenses avec `reward_log_names`. Sans SHLogs, la ligne est écrite dans la console.

## Jeu unique pour tout le réseau (SHNetwork)

Quand `SHNetwork` est installé et que `reseau.actif` vaut `true`, tous les serveurs partagent le même jeu :

- **Meneur.** La tâche de meneur `shchatgames` n'appartient qu'à un serveur à la fois. Lui seul décompte `interval`, tire le jeu au hasard et compte `min-players` sur tous les joueurs du réseau (`online()` de SHNetwork), pas seulement les siens. S'il tombe, un autre serveur devient meneur en quelques secondes ; l'ancien arrête son planificateur et abandonne sa partie sans rien annoncer.
- **Départ.** Le meneur publie `{type, content, answer, startedAt, endsAt}` sur le canal `chatgames`. Tous les serveurs, le meneur compris, affichent la question de la même manière qu'avant (texte, bouton cliquable, son).
- **Réponse.** Chaque serveur juge le chat sur place, sans attendre le réseau. Une bonne réponse (ou un clic valable) part vers le meneur sur `chatgames.reponse` : `{id, uuid, name, at}`. Un joueur n'envoie qu'une réponse par jeu.
- **Gagnant.** Le meneur retient le premier essai reçu pour le jeu en cours et ignore les suivants, ainsi que ceux d'un autre jeu. Il compte la victoire en base, puis publie `{id, winner, name, time, wins}` : tous les serveurs annoncent le gagnant. Les joueurs qui ont cliqué trop tard, même sur un autre serveur, reçoivent « Trop tard ! ».
- **Récompense.** Le meneur tire le groupe de `rewards` et envoie chaque commande par `runFor` de SHNetwork : elle est exécutée une seule fois, sur le serveur où se trouve le gagnant, ou à sa prochaine connexion s'il est parti. La ligne SHLogs est écrite sur le meneur.
- **Fin sans gagnant.** Au bout de `game_timeout_seconds`, le meneur publie la fin et tous les serveurs affichent le texte `timeout`.
- **Reprise.** Tous les serveurs retiennent la fin prévue du dernier jeu annoncé. Un nouveau meneur ne lance rien avant cette heure plus `reseau.marge-reprise-secondes`, et ne reprend jamais la partie de son prédécesseur. Les horloges des serveurs doivent être synchronisées (même machine ou NTP).

La réponse attendue est publiée en clair dans le message de départ : chaque serveur doit la connaître pour juger le chat sans attendre le bus. Un condensat n'apporterait rien, les réponses possibles sont quelques centaines de mots et des nombres à trois chiffres, et seuls les plugins des serveurs lisent le bus.

### Victoires

Avec SHNetwork, les victoires sont dans la table `chatgames_wins (uuid, name, wins)` de la base `bo_net`, incrémentées par `wins = wins + 1` en une seule instruction : tous les serveurs, donc tous les meneurs successifs, voient les mêmes compteurs. `%shchatgames_wins%` lit un cache de `reseau.cache-victoires-secondes` secondes, rempli à la connexion du joueur et mis à jour à chaque victoire annoncée ; il ne fait jamais attendre le serveur. Si la base ne répond pas, le gagnant est annoncé avec « ? » à la place de son total, sans perdre sa récompense.

`wins.yml` n'est plus écrit dans ce mode. `/shchatgames import` en reprend les totaux dans la base (une fois, sur un serveur, avant les premiers jeux) : un joueur qui a déjà une ligne n'est pas modifié, donc la commande peut être relancée sans rien compter deux fois.

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée `bo_net.chatgames_wins` et l'utilisateur `shchatgames` avec les seuls droits SELECT, INSERT, UPDATE). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHChatGames/config.yml`, section `reseau` : `url` et `mot-de-passe`. La même base sur tous les serveurs.
3. Poser le jar sur chaque serveur, avec SHNetwork. Le jar charge HikariCP et le pilote MariaDB par `libraries:` (le serveur ne les embarque pas) ; sans SHNetwork, ils ne sont pas utilisés.

Au démarrage, la console indique `SHNetwork détecté : un seul jeu du chat pour tout le réseau`. `/shnetwork info` donne le serveur meneur de `shchatgames`.

### Essayer rapidement

- `/shchatgames start` (ou `/shchatgames start solve`) sur le serveur meneur lance un jeu tout de suite, sans tenir compte de `min-players`. Sur un autre serveur, la commande répond qu'il n'est pas meneur.
- `interval: "10"` et `min-players: 1` dans `config.yml` donnent un jeu environ toutes les dix secondes ; `game_timeout_seconds` borne sa durée, et un nouveau jeu n'est lancé qu'une fois le précédent terminé.
- Arrêter le meneur : l'autre serveur reprend la tâche, attend la fin du jeu en cours plus la marge, puis recommence à décompter `interval`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shchatgames reload` | — | Relit la configuration, les messages et la liste de mots ; relance le planificateur avec le nouvel `interval`. Si `config.yml` ou `messages.yml` est invalide, rien n'est remplacé. | `shchatgames.admin.reload` |
| `/shchatgames start [jeu]` | — | Lance un jeu tout de suite (`type`, `unreverse`, `solve`, `sequence`, `click`). Avec SHNetwork, seulement sur le serveur meneur. | `shchatgames.admin.start` |
| `/shchatgames import` | — | Avec SHNetwork : reprend les victoires de `wins.yml` dans la base du réseau. | `shchatgames.admin.import` |
| `/shchatgames status` | — | Mode, jeu en cours, prochain lancement et joueurs comptés contre `min-players`. | `shchatgames.admin.status` |
| `/shcgclick` | — | Commande interne déclenchée par le clic sur la case. | `shchatgames.play` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shchatgames.admin` | Toutes les sous-commandes de `/shchatgames`. | `op` |
| `shchatgames.admin.reload` | `/shchatgames reload`. | `op` |
| `shchatgames.admin.start` | `/shchatgames start`. | `op` |
| `shchatgames.admin.import` | `/shchatgames import`. | `op` |
| `shchatgames.admin.status` | `/shchatgames status`. | `op` |
| `shchatgames.play` | Permet de participer au mini-jeu du clic. | `true` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHChatGames/
├── <a href="src/main/resources/config.yml">config.yml</a>
├── <a href="src/main/resources/messages.yml">messages.yml</a>
└── <a href="src/main/resources/words.yml">words.yml</a>
</pre>

La table des victoires du mode réseau est décrite dans <a href="sql/schema.sql">sql/schema.sql</a>.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
