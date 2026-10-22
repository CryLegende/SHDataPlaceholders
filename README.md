<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHDataPlaceholders

*Valeurs numériques par joueur (add, take, set) exposées en placeholders %shdataplaceholders_<clé>%.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Valeurs numériques par joueur (add, take, set) exposées en placeholders %shdataplaceholders_<clé>%.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `PlaceholderAPI`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shdataplaceholders` | `/shdata`, `/statsdata` | Commande principale pour gérer les stats | `shdata.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shdata.admin` | Permet de modifier les données | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHDataPlaceholders/
└── <a href="src/main/resources/config.yml">config.yml</a>
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
