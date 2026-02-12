package com.example.musicwebdav;

import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.config.AppPlaylistProperties;
import com.example.musicwebdav.common.config.AppSearchProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.config.AppScanProperties;
import com.example.musicwebdav.common.config.AppWebDavProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.musicwebdav.infrastructure.persistence.mapper")
@EnableScheduling
@EnableConfigurationProperties({
        AppAuthProperties.class,
        AppSecurityProperties.class,
        AppWebDavProperties.class,
        AppScanProperties.class,
        AppPlaylistProperties.class,
        AppSearchProperties.class
})
public class MusicWebdavApplication {

    public static void main(String[] args) {
        SpringApplication.run(MusicWebdavApplication.class, args);
    }
}
