package com.zifang.z.skill.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core 的主代码不许引用 servlet API —— 这是一条装配契约, 不是风格偏好.
 *
 * <p>servlet-api 在 core 里是 {@code provided}, 不会传递到下游模块的类路径上; 而 Spring 创建
 * {@code @Component} bean 时要反射方法签名. 于是"非 web 宿主只想用聚合能力、把 core 整个
 * component-scan 进容器"这条路, 会因为任何一个主类在签名里写了 {@code HttpServletRequest} 而
 * 直接崩在 {@code Failed to introspect Class}(实测踩过).
 *
 * <p>方法体里用 servlet 类型不会在装配期被解析, 但照样要求运行期类路径里有它, 所以这里按源码整体拦,
 * 而不是只看签名.
 */
class CoreStaysServletFreeTest {

    @Test
    void coreMainCodeReferencesNoServletApi() throws IOException {
        Path main = mainSources();
        List<String> offenders = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(main)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".java"))::iterator) {
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (src.contains("javax.servlet") || src.contains("jakarta.servlet")) {
                    offenders.add(main.relativize(p).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "core 主代码又引用 servlet API 了(它不会传递到 z-skill-starter 的类路径, 非 web 宿主装配即崩): "
                        + offenders + "; 兼容层取路径请走 request 属性, 见 SkillCompatController#pathTail");
    }

    /** 找不到源码目录本身就是失败 —— 空集合会让这条守卫"永远绿". */
    private static Path mainSources() {
        Path module = Paths.get("src/main/java");
        Path fromRepoRoot = Paths.get("z-skill-core/src/main/java");
        assertTrue(Files.isDirectory(module) || Files.isDirectory(fromRepoRoot),
                "没找到 core 的主源码目录(cwd=" + Paths.get("").toAbsolutePath() + "), 这条守卫测不到任何东西");
        return Files.isDirectory(module) ? module : fromRepoRoot;
    }
}
