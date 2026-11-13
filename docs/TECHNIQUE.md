# Détails techniques (moteur v2)

Le même moteur existe en Java (`Renderer.java`, cette appli) et en C (`src/julia-render.c` de la [version Linux](https://github.com/LouisBrusset/PROJ-julia-wallpaper-linux)). Les deux suivent exactement le même algorithme et consomment l'aléatoire dans le même ordre : pour une même graine, ils produisent la même fractale.

## Étapes d'un rendu

1. **Type de fractale** tiré au hasard (pondéré).
2. **Paramètres** : plusieurs jeux sont tirés pour ce type, puis notés sur un petit aperçu. Le meilleur est gardé, avec un peu de hasard dans la note pour varier.
3. **Cadrage** : soit l'objet entier, soit un zoom plein écran.
4. **Palette** tirée au hasard, en version nuit et en version jour.
5. **Rendu** multi-thread, anti-crénelage adaptatif, puis écriture des deux PNG.

## Les types de fractales

| Type | Poids | Ce qui est tiré au hasard |
|---|---|---|
| Julia `z² + c` | 22 % | `c` près du bord de Mandelbrot, selon 5 familles : poussière juste dehors, bord de la cardioïde (`c = λ/2 − λ²/4`, \|λ\| ≈ 1), bord du bulbe de période 2, intérieur près du bord, valeurs classiques perturbées |
| Julia multi `z^d + c` | 10 % | degré `d` de 3 à 6, `c` près du bord du Multibrot de degré `d` |
| Phoenix `z² + c + p·z(n−1)` | 5 % | `c` et `p` autour des valeurs classiques (0,567 et −0,5) |
| Mandelbrot (zoom) | 14 % | degré 2 (70 %) ou 3–4, point du bord trouvé par rejet (le point s'échappe après au moins 25 à 145 itérations), zoom de 7× à 6000× ; parfois l'ensemble entier |
| Burning Ship (zoom) | 6 % | la petite « épave » emblématique, l'ensemble entier ou un point du bord |
| Tricorne | 3 % | l'ensemble entier (symétrie d'ordre 3) ou un zoom modéré |
| Newton | 9 % | racines de `zⁿ − 1` (n de 3 à 7, tourné) ou 3 à 6 racines au hasard, relaxation `a` (méthode de Newton généralisée), vue d'ensemble ou zoom |
| Lyapunov | 7 % | suite A/B (AB, AABAB, BBBBBBAAAAAA… ou au hasard), fenêtre dans `[2, 4]²` |
| Tapis de Sierpinski généralisé | 8 % | grille 3×3, 4×4 ou 5×5, masque de cases gardées à symétrie du carré (tapis classique, éponge de Menger vue de face, fractale de Vicsek, poussière de Cantor, et des dizaines d'autres) |
| Triangle de Sierpinski | 5 % | sous-triangle(s) retiré(s) : le milieu (classique) ou un coin, ou deux |
| IFS (jeu du chaos) | 11 % | fougère de Barnsley, dragon de Heighway, courbe C de Lévy, courbe de Koch, n-flocons (pentaflake, hexaflake…), spirales, rosaces symétriques, IFS affines aléatoires |

**Score des candidats.**
- *Objets* (Julia, Phoenix) : aperçu 200×200 de `[−2, 2]²`, puis densité de contours, remplissage de la boîte englobante, part d'intérieur et rapport largeur/hauteur.
- *Zooms* (Mandelbrot, Burning Ship, Tricorne, Newton, Lyapunov) : aperçu de la fenêtre, puis densité de contours, écart-type des valeurs et part d'intérieur. Les zones trop bruitées sont pénalisées.
- *IFS* : 40 000 points, puis couverture d'une grille et rapport largeur/hauteur.

## Cadrage

Pour un objet entier, une analyse en composantes principales donne son axe principal. L'image est tournée pour que cet axe suive la grande dimension de l'écran (en portrait sur téléphone, la hauteur). Le rapport largeur/hauteur peut aller de 1:1 jusqu'au format de l'écran, et seuls les objets plus allongés sont pénalisés. La marge varie de 6 à 45 %, ce qui fait varier aussi la taille apparente.

Les zooms remplissent tout l'écran, avec une rotation au hasard.

Taille d'écran : mode natif de la plus grande sortie connectée (`/sys/class/drm` sous Linux), taille maximale de fenêtre sous Android.

## Couleurs

Chaque échantillon donne une valeur `t ∈ [0, 1]` (0 = fond, 1 = au contact de la fractale) ou une valeur « intérieur ».
- *Types à temps de fuite* : temps de fuite lissé (`n + 1 − log_d log|z|`) en échelle logarithmique, normalisé sur les centiles de l'image. S'y ajoutent un liseré par estimation de distance (`|z| log|z| / |z'|`) et un léger relief (direction de `z / z'`).
- *Newton* : vitesse de convergence. Avec une palette multicolore, chaque racine reçoit sa propre bande de couleurs.
- *Lyapunov* : exposant de Lyapunov. Les zones stables suivent le dégradé, les zones chaotiques prennent la couleur d'intérieur.
- *Tapis et triangle* : profondeur à laquelle un trou apparaît, avec une lueur près des bords des trous.
- *IFS* : densité de points du jeu du chaos, en échelle logarithmique.

**Palettes**, générées en **OKLCH** (luminance, chroma, teinte perceptuellement régulières) :

| Famille | Poids | Principe |
|---|---|---|
| Thème prédéfini | 15 % | aurore, braise, nébuleuse, océan, jade |
| Monochrome | 35 % | une teinte au hasard, avec une légère dérive |
| Bicolore | 20 % | deux teintes complémentaires, opposées ou voisines |
| Tricolore | 12 % | triade (120°) ou trois teintes voisines |
| Arc-en-ciel | 18 % | la teinte tourne de 240° à 420° le long du dégradé |

- **Nuit (néon)** : fond presque noir teinté, puis la couleur monte en chroma jusqu'à un ton lumineux presque blanc.
- **Jour (pastel)** : fond teinté à 86 % de luminance, jamais blanc, puis la couleur descend vers un ton profond.

Les couleurs hors du gamut sRGB voient leur chroma réduite, à teinte et luminance constantes. Les dégradés sont interpolés en OKLab, mélangés en RVB linéaire et tramés à la quantification en 8 bits.

## Performance et mémoire

- Calcul multi-thread (OpenMP en C, threads de priorité basse sous Android).
- Détection de cycle (Brent) : les points intérieurs s'arrêtent tôt.
- Nombre d'itérations adapté à chaque image (300 à 2500).
- Anti-crénelage adaptatif : seuls les pixels contrastés sont sur-échantillonnés (4 échantillons en grille tournée).
- 4 octets par pixel pendant le calcul (valeur et relief sur 16 bits), plus 4 octets pour les IFS. Sous Android, les images couleur sont compressées en PNG bande par bande, sans jamais être entières en mémoire.
- Temps typique pour 2560×1600 ou 1080×2400 : de 0,5 à 9 secondes de CPU selon le type. Le calcul se fait en priorité minimale, juste après le réveil.

## Robustesse aux mises à jour de GNOME

Tout passe par des interfaces stables depuis des années : `gsettings` (clés `org.gnome.desktop.background`), le signal `PrepareForSleep` de logind et `org.gnome.ScreenSaver.ActiveChanged`. Aucune extension GNOME, aucun fichier système modifié, rien à réinstaller après une mise à jour. Chaque image a un nom unique, donc GNOME ne peut pas afficher une ancienne version gardée en cache.
