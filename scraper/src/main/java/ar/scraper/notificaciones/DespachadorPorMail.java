package ar.scraper.notificaciones;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DespachadorPorMail implements NotificacionDespachador {

    private final PlantillaBajaPrecio plantilla;
    private final MailPort mail;
    private final EnlaceBaja enlaces;

    public DespachadorPorMail(PlantillaBajaPrecio plantilla, MailPort mail) {
        this(plantilla, mail, null);
    }

    @Autowired
    public DespachadorPorMail(PlantillaBajaPrecio plantilla, MailPort mail, EnlaceBaja enlaces) {
        this.plantilla = plantilla;
        this.mail = mail;
        this.enlaces = enlaces;
    }

    @Override
    public Map<String, Entrega> despachar(Map<String, List<BajaReclamada>> bajasPorDestinatario) {
        List<CorreoDirigido> correos = new ArrayList<>();
        bajasPorDestinatario.forEach((email, bajas) -> {
            String enlace = enlaces == null ? null : enlaces.para(bajas.getFirst().usuarioId()).orElse(null);
            correos.add(new CorreoDirigido(email, plantilla.renderizar(bajas, enlace), clave(bajas), enlace));
        });
        return mail.enviar(correos);
    }

    /** Same recipient and same rows give the same key, whatever order the rows arrive in. */
    private static String clave(List<BajaReclamada> bajas) {
        String ids = bajas.stream().map(BajaReclamada::id).sorted().map(String::valueOf).collect(Collectors.joining(","));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((bajas.getFirst().usuarioId() + ":" + ids).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
