<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHChatGames

*Plugin du serveur SkyHand.*

<img src="https://img.shields.io/badge/Paper-1.20.2-f2a900?style=for-the-badge" alt="Paper 1.20.2"> <img src="https://img.shields.io/badge/Java-17-e76f00?style=for-the-badge" alt="Java 17"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Plugin du serveur SkyHand.

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

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`
- `words.yml`

## Compilation

Java 17 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
