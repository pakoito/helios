package kategory

import arrow.*
import arrow.core.*
import arrow.data.*
import arrow.typeclasses.*
import arrow.optics.Traversal
import arrow.optics.function.FilterIndex
import arrow.optics.function.filterIndex
import arrow.optics.instances.listToListKW
import arrow.optics.instances.stringToList


@instance(ListKW::class)
interface ListKWFilterIndexInstance<A> : FilterIndex<ListKWKind<A>, Int, A> {
    override fun filter(p: (Int) -> Boolean): Traversal<ListKWKind<A>, A> = FilterIndex.fromTraverse<ListKWHK, A>({ aas ->
        aas.ev().mapIndexed { index, a -> a toT index }.k()
    }, ListKW.traverse()).filter(p)
}

@instance(NonEmptyList::class)
interface NonEmptyListFilterIndexInstance<A> : FilterIndex<NonEmptyListKind<A>, Int, A> {
    override fun filter(p: (Int) -> Boolean): Traversal<NonEmptyListKind<A>, A> = FilterIndex.fromTraverse<NonEmptyListHK, A>({ aas ->
        aas.ev().all.mapIndexed { index, a -> a toT index }.let {
            NonEmptyList.fromListUnsafe(it)
        }
    }, NonEmptyList.traverse()).filter(p)
}

@instance(SequenceKW::class)
interface SequenceKWFilterIndexInstance<A> : FilterIndex<SequenceKWKind<A>, Int, A> {
    override fun filter(p: (Int) -> Boolean): Traversal<SequenceKWKind<A>, A> = FilterIndex.fromTraverse<SequenceKWHK, A>({ aas ->
        aas.ev().mapIndexed { index, a -> a toT index }.k()
    }, SequenceKW.traverse()).filter(p)
}

@instance(MapKW::class)
interface MapKWFilterIndexInstance<K, V> : FilterIndex<MapKWKind<K, V>, K, V> {

    override fun filter(p: (K) -> Boolean): Traversal<MapKWKind<K, V>, V> = object : Traversal<MapKWKind<K, V>, V> {
        override fun <F> modifyF(FA: Applicative<F>, s: HK<HK<MapKWHK, K>, V>, f: (V) -> HK<F, V>): HK<F, HK<HK<MapKWHK, K>, V>> =
                ListKW.traverse().traverse(s.ev().map.toList().k(), { (k, v) ->
                    FA.map(if (p(k)) f(v) else FA.pure(v)) {
                        k to it
                    }
                }, FA).let {
                    FA.map(it) {
                        it.toMap().k()
                    }
                }

    }
}

object StringFilterIndexInstance : FilterIndex<String, Int, Char> {
    override fun filter(p: (Int) -> Boolean): Traversal<String, Char> =
            stringToList compose listToListKW() compose filterIndex<ListKW<Char>, Int, Char>().filter(p)
}

object StringFilterIndexInstanceImplicits {
    @JvmStatic fun instance(): StringFilterIndexInstance = StringFilterIndexInstance
}
