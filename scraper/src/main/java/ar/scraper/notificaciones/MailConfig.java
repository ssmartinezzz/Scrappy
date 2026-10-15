package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The feature turns on by itself when {@code SMTP_HOST} is set; there is no separate flag. */
@Configuration
public class MailConfig {

    @Bean
    MailPort mailPort(@Value("${smtp.host:}") String host,
                      @Value("${smtp.port:587}") int port,
                      @Value("${smtp.username:}") String username,
                      @Value("${smtp.password:}") String password,
                      @Value("${smtp.from-address:}") String from,
                      @Value("${smtp.starttls-required:true}") boolean starttlsObligatorio) {
        return StringUtils.isBlank(host)
                ? new ConsoleMailAdapter()
                : new SmtpMailAdapter(host, port, username, password, from, starttlsObligatorio);
    }
}
