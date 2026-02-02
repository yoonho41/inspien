package com.inspien.infra;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Properties;

@Slf4j
@Component
public class SftpUploader {

    @Value("${inspien.sftp.host}")
    private String host;

    @Value("${inspien.sftp.port:22}")
    private int port;

    @Value("${inspien.sftp.user}")
    private String user;

    @Value("${inspien.sftp.password}")
    private String password;

    @Value("${inspien.sftp.file-path}")
    private String remoteDir;

    @Value("${inspien.sftp.strict-host-key-checking:false}")
    private boolean strictHostKeyChecking;

    @Value("${inspien.sftp.server-host-key:ssh-rsa}")
    private String serverHostKeyAlgos;


    public void upload(Path localFile, String remoteFileName) {
        Session session = null;
        ChannelSftp sftp = null;

        try {
            JSch jsch = new JSch();

            session = jsch.getSession(user, host, port);
            session.setPassword(password);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", strictHostKeyChecking ? "yes" : "no");
            config.put("server_host_key", serverHostKeyAlgos);
            session.setConfig(config);

            // 서버 접속
            log.info("SFTP connecting... host={}, port={}, user={}, remoteDir={}", host, port, user, remoteDir);
            session.connect(15_000);

            Channel channel = session.openChannel("sftp");
            channel.connect(15_000);
            sftp = (ChannelSftp) channel;

            sftp.cd(remoteDir);

            // 파일 업로드
            log.info("SFTP uploading... localFile={}, remoteFileName={}", localFile, remoteFileName);
            sftp.put(localFile.toString(), remoteFileName);

            log.info("SFTP upload success. remote={}/{}", remoteDir, remoteFileName);

        } catch (Exception e) {
            throw new RuntimeException("SFTP upload failed: " + e.getMessage(), e);

        } finally {
            // 리소스 정리
            if (sftp != null) sftp.disconnect();
            if (session != null) session.disconnect();
        }
    }
}
