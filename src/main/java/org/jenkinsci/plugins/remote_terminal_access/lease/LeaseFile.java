package org.jenkinsci.plugins.remote_terminal_access.lease;

import hudson.model.PageDecorator;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import jenkins.model.Jenkins;
import org.jenkinsci.main.modules.sshd.PortAdvertiser;
import org.jenkinsci.main.modules.sshd.SSHD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.URL;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Remembers the in-scope session in the current directory.
 *
 * @author Kohsuke Kawaguchi
 */
public class LeaseFile implements Serializable {
    private transient final Channel channel;

    public LeaseFile(Channel channel) {
        this.channel = channel;
    }

    /**
     * Deletes the existing lease file.
     */
    public void clear() throws InterruptedException, IOException {
        channel.call(new Callable<Void,IOException>() {
            public Void call() throws IOException {
                File d = new File(".").getAbsoluteFile();
                while (d!=null) {
                    File lease = new File(d,LEASE_FILE);
                    if (lease.exists()) {
                        lease.delete();
                        new File(lease.getParentFile(),SSHCONFIG_FILE).delete();
                        break;
                    }
                    d = d.getParentFile();
                }
                return null;
            }
        });
    }

    /**
     * Gets the current lease context if one is available.
     */
    public LeaseContext get() throws InterruptedException, IOException {
        Properties props = channel.call(new Callable<Properties,IOException>() {
            public Properties call() throws IOException {
                File d = new File(".").getAbsoluteFile();
                while (d!=null) {
                    File lease = new File(d,LEASE_FILE);
                    if (lease.exists()) {
                        Properties props = new Properties();
                        FileInputStream in = new FileInputStream(lease);
                        try {
                            props.load(in);
                        } finally {
                            in.close();
                        }
                        return props;
                    }
                    d = d.getParentFile();
                }
                return null;
            }
        });

        if (props!=null && Jenkins.SESSION_HASH.equals(props.getProperty("session")))
            return LeaseContext.getById(props.getProperty("lease"));

        return null;
    }

    /**
     * Generates ".jenkins.lease" file and the equivalent ssh_config file.
     */
    public void set(LeaseContext context) throws InterruptedException, IOException {
        final String id = context.id;
        final String session = Jenkins.SESSION_HASH;        final Set<String> aliases = new HashSet<String>(context.getAliases());
        final int sshPort = SSHD.get().getActualPort();
        String host = PageDecorator.all().get(PortAdvertiser.class).host;
        if (host==null) host = new URL(Jenkins.getInstance().getRootUrl()).getHost();
        final String sshHost = host;

        channel.call(new Callable<Void, IOException>() {
            public Void call() throws IOException {
                File lease = new File(LEASE_FILE);
                Properties props = new Properties();
                props.setProperty("session", session);
                props.setProperty("lease", id);

                FileOutputStream out = new FileOutputStream(lease);
                try {
                    props.store(out, null);
                } finally {
                    out.close();
                }

                FileWriter w = new FileWriter(SSHCONFIG_FILE);
                PrintWriter pw = new PrintWriter(w);

                for (String alias : aliases) {
                    pw.println("Host=" + alias);
                    pw.printf("ProxyCommand=ssh -p %d %s lease-tunnel %s %s",
                            sshPort, sshHost, id, alias);
                    pw.println();
                }
                pw.close();

                return null;
            }
        });
    }

    private static final long serialVersionUID = 1L;

    public static final String LEASE_FILE = "Leasefile";
    public static final String SSHCONFIG_FILE = "Leasefile.ssh";
}
