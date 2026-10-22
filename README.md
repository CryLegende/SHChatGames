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

## Dépendances

- Obligatoires : `PlaceholderAPI`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shchatgames` | — | Commande principale pour SHChatGames. | `shchatgames.reload` |
| `/shcgclick` | — | Commande interne declenchee par le clic sur la case. | `shchatgames.play` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shchatgames.reload` | Permet de recharger la configuration de SHChatGames. | `op` |
| `shchatgames.play` | Permet de participer au mini-jeu du clic. | `true` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHChatGames/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/words.yml">words.yml</a>
</pre>

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
