package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class AndroidIdProxy extends ClassInvocationStub {
    public static final String TAG = "AndroidIdProxy";

    public AndroidIdProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static String getFakeAndroidId() {
        try {
            String pkg = BActivityThread.getAppPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                return DeviceProfileManager.get().getAndroidId(pkg);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Could not get package name for AndroidId, using fallback", e);
        }
        return generateRandomAndroidId();
    }

    @ProxyMethod("getAndroidId")
    public static class GetAndroidId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String fakeId = getFakeAndroidId();
            Slog.d(TAG, "AndroidId: getAndroidId -> " + fakeId);
            return fakeId;
        }
    }

    @ProxyMethod("getString")
    public static class GetString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                        key.contains("secure_id") || key.contains("device_id")) {
                        String fakeId = getFakeAndroidId();
                        Slog.d(TAG, "AndroidId: getString(" + key + ") -> " + fakeId);
                        return fakeId;
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: GetString error", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("getLong")
    public static class GetLong extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                        key.contains("secure_id") || key.contains("device_id")) {
                        try {
                            return Long.parseLong(getFakeAndroidId(), 16);
                        } catch (NumberFormatException ignored) {
                            return generateMockAndroidIdLong();
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: GetLong error", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("get")
    public static class Get extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                        key.contains("secure_id") || key.contains("device_id")) {
                        String fakeId = getFakeAndroidId();
                        Slog.d(TAG, "AndroidId: get(" + key + ") -> " + fakeId);
                        return fakeId;
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: Get error", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("read")
    public static class Read extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                        key.contains("secure_id") || key.contains("device_id")) {
                        String fakeId = getFakeAndroidId();
                        Slog.d(TAG, "AndroidId: read(" + key + ") -> " + fakeId);
                        return fakeId;
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: Read error", e);
                return method.invoke(who, args);
            }
        }
    }

    private static String generateRandomAndroidId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)));
        }
        return sb.toString().toUpperCase();
    }

    private static Long generateMockAndroidIdLong() {
        return (long) (Math.random() * Long.MAX_VALUE);
    }
}
