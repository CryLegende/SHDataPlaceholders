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
- Optionnelle : `SHNetwork` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent les valeurs des joueurs :

- les valeurs sont dans MariaDB (table `shdataplaceholders_values` : `uuid`, `stat`, `value`) ; `add` et `take` sont faits par la base (`value = value + ?`, la ligne verrouillée le temps de l'opération) : deux serveurs qui modifient la même valeur au même moment s'additionnent, aucun ne repart d'une valeur lue plus tôt ; le résultat reste dans les bornes d'un entier ;
- `/shdata` marche pour un joueur de ce serveur, d'un autre serveur ou hors ligne : le pseudo est retrouvé parmi les joueurs connectés au réseau, dans la table des comptes (`reseau.table-comptes`, `Global.sh_track_firstseen`), sinon parmi les joueurs déjà venus sur ce serveur ; jamais de recherche Mojang ;
- le serveur qui modifie une valeur prévient les autres par le bus (sujet `reseau.canal`) : celui où le joueur est connecté relit la clé en base ;
- les placeholders lisent la mémoire, jamais la base ; à l'arrivée d'un joueur ses valeurs sont relues une fois le départ de l'autre serveur confirmé par SHNetwork (d'ici là, valeur par défaut) ;
- au départ d'un joueur, SHNetwork attend que les écritures en cours soient finies ;
- `userdata/<uuid>.json` n'est plus lu ni écrit : `/shdata import` les reprend une fois dans la base, sans écraser les valeurs déjà présentes.

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : fichiers locaux. Si la base est injoignable, les commandes répondent par une erreur et les placeholders gardent la valeur par défaut. La table se crée toute seule au démarrage, ou à la main avec `sql/schema.sql`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shdataplaceholders` | `/shdata`, `/statsdata` | Commande principale pour gérer les stats (`add`, `take`, `set`, `reload`, `import`) | `shdata.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shdata.admin` | Permet de modifier les données | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHDataPlaceholders/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
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
