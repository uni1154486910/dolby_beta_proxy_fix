package com.raincat.dolby_beta.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.raincat.dolby_beta.BuildConfig;
import com.raincat.dolby_beta.Hook;
import com.raincat.dolby_beta.net.HTTPSTrustManager;
import com.raincat.dolby_beta.utils.Tools;
import com.stericson.RootShell.execution.Command;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/26
 *     desc   : 脚本帮助类
 *     version: 1.0
 * </pre>
 */

public class ScriptHelper {
    //模块路径
    public static String modulePath;
    //脚本路径
    private static String scriptPath;
    //node路径
    private static String nodeLibPath;

    private static final String[] STOP_PROXY = new String[]{"node=$(ps -ef |grep \"libnode.so app.js\" |grep -v grep)",
            "if [ -n \"$node\" ]; then",
            "killall -9 libnode.so >/dev/null 2>&1",
            "fi"};

    @SuppressLint("StaticFieldLeak")
    private static Context neteaseContext;

    private static String getScriptPath(Context context) {
        if (TextUtils.isEmpty(scriptPath))
            scriptPath = context.getFilesDir().getAbsolutePath() + "/script";
        return scriptPath;
    }

    /**
     * 初始化脚本
     *
     * @param cover 是否覆盖
     */
    public static void initScript(Context context, boolean cover) {
        File unblockFile = new File(getScriptPath(context));
        neteaseContext = context;
        if (cover || !unblockFile.exists() || !(BuildConfig.VERSION_CODE + "").equals(ExtraHelper.getExtraDate(ExtraHelper.APP_VERSION))) {
            if (FileHelper.unzipFile(modulePath, getScriptPath(context), "assets", "UnblockNeteaseMusic.zip")) {
                FileHelper.unzipFiles(getScriptPath(context) + "/UnblockNeteaseMusic.zip", getScriptPath(context));
            }
            Command auth = new Command(0, "cd " + getScriptPath(context), "chmod 0777 *");
            Tools.shell(auth);
            ExtraHelper.setExtraDate(ExtraHelper.APP_VERSION, BuildConfig.VERSION_CODE);
        }
        // 关键修复：把 libnode.so 解压到脚本目录，并用相对路径执行。
        // LSPatch 嵌入模式下 modulePath 指向缓存内的模块 APK，原有 PATH 拼接
        // (modulePath上级/lib/arm64 与 modulePath!/lib/arm64-v8a) 均无法命中，
        // 导致 "libnode.so app.js" 永远报 not found，脚本静默失败。
        ensureNodeBinary(context);
        nodeLibPath = "";
    }

    /**
     * 确保脚本目录下存在可执行的 libnode.so。
     *
     * 注意：不能用跨应用路径复制。模块在 LSPatch 嵌入模式下运行在网易云进程里，
     * 属于 untrusted_app 域，读另一个应用（com.raincat.dolby_beta）的
     * /data/app/.../lib/ 会被 SELinux 拦截，且 cat 失败没有任何提示。
     *
     * 因此改为：把 libnode.so 打包进模块 APK 的 assets，由这里用 Java 直接解压，
     * 全程只操作本应用私有目录，不需要 root，也不跨应用。
     */
    private static void ensureNodeBinary(Context context) {
        final File target = new File(getScriptPath(context) + "/libnode.so");
        if (target.exists() && target.length() > 1024 * 1024) {
            return;
        }
        InputStream is = null;
        java.io.FileOutputStream fos = null;
        try {
            // assets/libnode.so（构建时由 gradle 从 jniLibs 复制过来）
            is = context.getAssets().open("libnode.so");
            fos = new java.io.FileOutputStream(target);
            byte[] buf = new byte[81920];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            XposedBridge.log("[dolby_beta] libnode.so extracted -> " + target.length() + " bytes");
        } catch (Throwable e) {
            XposedBridge.log("[dolby_beta] extract libnode.so failed: " + e);
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
        // 赋予可执行权限（Java 无法直接 chmod，这里用 shell 只做 chmod）
        Command chmod = new Command(0, "chmod 755 " + target.getAbsolutePath());
        Tools.shell(chmod);
    }
    public static void startHttpProxyMode(final Context context) {
        stopScript();
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "1");
        Tools.showToastOnLooper(context, "服务器代理运行成功");
    }

    public static void startScript() {
        // 用端口 + 文件检测替代 ps -ef：Android 10+ 非 root 应用只能看到自己
        // uid 的进程，ps -ef | grep libnode.so 常误判为空，导致重复启动或误杀。
        final int port = SettingHelper.getInstance().getProxyPort();
        String[] START_PROXY = new String[]{
                "if [ -f " + scriptPath + "/libnode.so ]; then",
                "  cd " + scriptPath,
                "  " + nodeLibPath + " nohup setsid ./libnode.so app.js -a 127.0.0.1 -o " +
                        SettingHelper.getInstance().getProxyOriginal() + " -p " + port + ":" + (port + 1) +
                        " >> " + scriptPath + "/node.log 2>&1 &",
                "  echo STARTED",
                "else",
                "  echo 'Error: libnode.so missing in " + scriptPath + "'",
                "fi"};
        Command start = new Command(0, START_PROXY) {
            @Override
            public void commandOutput(int id, String line) {
                if ((!line.contains("mERROR") && line.contains("Error:")) || line.contains("Port ") || line.contains("Please ")) {
                    Intent intent = new Intent(Hook.msg_send_notification);
                    intent.putExtra("message", line);
                    intent.putExtra("title", "脚本产生如下错误信息，若脚本因此无法运行请提issue");
                    if (neteaseContext != null)
                        neteaseContext.sendBroadcast(intent);
                } else if (line.contains("HTTP Server running") || line.contains("STARTED")) {
                    if (neteaseContext != null && !ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
                        Tools.showToastOnLooper(neteaseContext, "UnblockNeteaseMusic运行成功");
                    ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "1");
                } else if (line.equals("Killed ")) {
                    if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_master_key))
                        startScript();
                }
            }
        };
        Tools.shell(start);
    }

    public static void stopScript() {
        Command stop = new Command(0, STOP_PROXY);
        Tools.shell(stop);
    }

    /**
     * 获取CA证书
     */
    public static SSLSocketFactory getSSLSocketFactory(Context context) {
        SSLContext sslContext = null;
        try {
            File ca = new File(getScriptPath(context) + File.separator + "ca.crt");
            if (!SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key) && ca.exists()) {
                InputStream certificate = new FileInputStream(ca);
                Certificate certificate1 = CertificateFactory.getInstance("X.509").generateCertificate(certificate);
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setCertificateEntry("ca", certificate1);
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            } else {
                TrustManager[] trustManagers = new TrustManager[]{new HTTPSTrustManager()};
                try {
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, trustManagers, new SecureRandom());
                } catch (NoSuchAlgorithmException | KeyManagementException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (sslContext != null)
            return sslContext.getSocketFactory();
        else
            return null;
    }
}
