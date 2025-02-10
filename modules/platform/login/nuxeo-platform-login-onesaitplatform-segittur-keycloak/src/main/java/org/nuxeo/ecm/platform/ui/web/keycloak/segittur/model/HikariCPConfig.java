package org.nuxeo.ecm.platform.ui.web.keycloak.segittur.model;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class HikariCPConfig {

	private static String ONESAITPLATFORM_CONFIG_JDBC_URL_ENV="ONESAITPLATFORM_CONFIG_JDBC_URL";
	private static String ONESAITPLATFORM_CONFIG_JDBC_USER_ENV="ONESAITPLATFORM_CONFIG_JDBC_USER";
	private static String ONESAITPLATFORM_CONFIG_JDBC_PASSWORD_ENV="ONESAITPLATFORM_CONFIG_JDBC_PASSWORD";
	private static String ONESAITPLATFORM_CONFIG_JDBC_DRIVER_ENV="ONESAITPLATFORM_CONFIG_JDBC_DRIVER";
	
    private static HikariDataSource dataSource;

    static {
        try {
        	// Configuración de HikariCP
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(System.getenv(ONESAITPLATFORM_CONFIG_JDBC_URL_ENV)); 
            config.setUsername(System.getenv(ONESAITPLATFORM_CONFIG_JDBC_USER_ENV));
            config.setPassword(System.getenv(ONESAITPLATFORM_CONFIG_JDBC_PASSWORD_ENV));

            // Configuración adicional (opcional)
            config.setDriverClassName(System.getenv(ONESAITPLATFORM_CONFIG_JDBC_DRIVER_ENV)); 
            config.setMaximumPoolSize(10); // Máximo número de conexiones en el pool
            config.setMinimumIdle(2); // Mínimo número de conexiones inactivas
            config.setIdleTimeout(30000); // Tiempo de espera para cerrar conexiones inactivas
            config.setConnectionTimeout(20000); // Tiempo máximo para obtener una conexión
            config.setLeakDetectionThreshold(5000); // Detección de fugas de conexiones (opcional)

            // Inicializar el DataSource
            dataSource = new HikariDataSource(config);

        } catch (Exception e) {
            throw new RuntimeException("Error configuring HikariCP", e);
        }
    }

    // Método para obtener una conexión
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // Método para cerrar el pool al finalizar la aplicación
    public static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}

