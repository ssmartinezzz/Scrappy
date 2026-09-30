package ar.scraper.aggregator.grouping;

import java.util.Set;

final class StopWords {

    private StopWords() {}

    static final Set<String> STOP = Set.of(
        "negro","negra","blanco","blanca","azul","rojo","roja","verde","gris",
        "beige","naranja","amarillo","violeta","marron","celeste","rosa",
        "plateado","dorado","tostado","crudo","navy","khaki","oliva","militar",
        "ivory","offwhite","off","white","black","grey","gray","red","blue",
        "hombre","mujer","masculino","femenino","unisex","dama","caballero",
        "xs","xxs","s","m","l","xl","xxl","xxxl","unico","talle","talla","size",
        "nuevo","nueva","original","importado","coleccion","edicion","temporada",
        "de","la","el","los","las","con","para","en","y","a","e"
    );
}
