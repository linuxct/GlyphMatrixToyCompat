package space.linuxct.glyphmatrixtoycompat.ui

import androidx.annotation.StringRes
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The product name for a panel — what a **user** is shown wherever a variant has
 * to be named.
 *
 * The design format identifies a device by its Pokémon codename, and that is not
 * changing: `bellsprout` and `arbok` are the keys in the JSON, they are what an
 * exporter writes, and `docs/glyph-design-format.md` documents them for the
 * people writing exporters. But they are Nothing's internal names for hardware,
 * not names for a phone anybody owns — "arbok" on a card in the Create tab tells
 * a user nothing at all, and reads as a bug.
 *
 * So the split is: [PokemonCodename] keeps the codename as the format's identity,
 * and every place the UI *renders* one goes through here instead.
 *
 * **Why this is in `ui/` and not on the enum.** `core/design/` is pure Kotlin and
 * JVM-tested — it must not reference `R`, or the codec's tests would need a
 * resource loader. A string resource per device and a mapping in the UI layer
 * keeps `core` android-free and keeps the names localisable-in-principle in the
 * one place strings belong. (They are `translatable="false"`: product names are
 * the same in every locale.)
 *
 * `when` without an `else`, deliberately: adding a device to the format then
 * fails to compile here, which is the right place to be reminded that a new panel
 * needs a name a human can read.
 */
@StringRes
fun PokemonCodename.displayNameRes(): Int = when (this) {
    PokemonCodename.BELLSPROUT -> R.string.device_bellsprout
    PokemonCodename.ARBOK -> R.string.device_arbok
}
