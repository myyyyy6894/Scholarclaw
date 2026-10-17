package com.scholarclaw.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Strict DAG 的可执行断言（{@code 02} §4.1 / §4.3）。
 *
 * <p>{@code sc-java-backend} 是单 Maven 工程的<b>包级</b>模块化单体，编译器不会拦截跨模块依赖，
 * 因此依赖方向必须由本测试守护：新增一处未经允许的跨模块 import 会直接让构建失败。
 *
 * <p>每个模块只允许依赖架构文档 §4.3「编译期单向依赖的模块」列出的模块，外加自身与
 * {@code com.scholarclaw.common}（第 4 层公共基础设施）。
 *
 * <p><b>诚实说明当前防护强度：</b>模块包目前只含 {@code package-info.java}，而 ArchUnit 不为
 * {@code package-info} 记录依赖边，因此逐模块规则在 TASK-101 骨架下不可能命中——本阶段 DAG
 * 是平凡成立的。这些规则从 TASK-202 起（模块内出现真实业务类）开始具备实际拦截能力。
 * {@code moduleRuleShapeDetectsRealDependencies} 用同一规则形态对一处真实跨包依赖做负向对照，
 * 保证规则本身不是错误写法；{@code allModulePackagesArePresent} 保证包已存在。
 */
class ModuleDependencyTest {

    /** 模块 -> 架构文档允许其依赖的业务模块（不含自身与 common）。 */
    private static final List<ModuleRule> MODULE_RULES = List.of(
            new ModuleRule("userconfig", List.of()),
            new ModuleRule("auth", List.of("userconfig")),
            new ModuleRule("course", List.of("userconfig")),
            new ModuleRule("notification", List.of("userconfig")),
            new ModuleRule("ddl", List.of("course", "userconfig", "notification")),
            new ModuleRule("knowledge", List.of("course", "userconfig", "notification")),
            new ModuleRule("agent", List.of("ddl", "knowledge", "course", "userconfig", "notification")),
            new ModuleRule("channel", List.of("agent", "knowledge", "userconfig")),
            new ModuleRule("scheduler", List.of("ddl", "knowledge", "course", "userconfig"))
    );

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.scholarclaw");
    }

    static Stream<Arguments> moduleDependencyRules() {
        return MODULE_RULES.stream().map(rule -> Arguments.of(rule.module(), rule.allowedModules()));
    }

    @Test
    @DisplayName("九个模块包与公共层都已存在（防止规则退化为空层而假通过）")
    void allModulePackagesArePresent() {
        assertThat(productionClasses.that(resideInAPackage("com.scholarclaw.common..")))
                .as("公共基础设施层 com.scholarclaw.common 必须存在")
                .isNotEmpty();

        for (ModuleRule rule : MODULE_RULES) {
            String modulePackage = "com.scholarclaw.modules." + rule.module() + "..";
            assertThat(productionClasses.that(resideInAPackage(modulePackage)))
                    .as("模块包 %s 必须存在（依赖规则才有意义）", modulePackage)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("第 4 层公共基础设施不得依赖任何业务模块")
    void commonLayerMustNotDependOnBusinessModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.scholarclaw.common..")
                .should().dependOnClassesThat().resideInAPackage("com.scholarclaw.modules..");
        rule.check(productionClasses);
    }

    @Test
    @DisplayName("模块之间不得出现循环依赖")
    void moduleSlicesMustBeFreeOfCycles() {
        SlicesRuleDefinition.slices()
                .matching("com.scholarclaw.modules.(*)..")
                .should().beFreeOfCycles()
                .check(productionClasses);
    }

    @Test
    @DisplayName("负向对照：同一套规则形态确实能检出真实的跨包依赖，证明断言不是在空转")
    void moduleRuleShapeDetectsRealDependencies() {
        // 现状：模块包内只有 package-info.java，而 ArchUnit 不为其记录任何依赖边，
        // 因此逐模块规则在当前骨架下不可能命中（DAG 平凡成立）。为了让"通过"有意义，
        // 这里用完全相同的规则形态去检查一处确实存在的跨包依赖：
        // common.response.Result -> common.config.ScTimeZone，必须报出违规。
        ArchRule probe = noClasses()
                .that().resideInAPackage("com.scholarclaw.common.response..")
                .should().dependOnClassesThat().resideInAnyPackage("com.scholarclaw.common.config..");

        assertThatThrownBy(() -> probe.check(productionClasses))
                .as("若此断言不报错，说明规则形态无法检出真实依赖，则逐模块 DAG 规则也是失效的")
                .isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest(name = "{0} 只能依赖 {1}")
    @MethodSource("moduleDependencyRules")
    @DisplayName("各模块只能依赖架构文档允许的模块")
    void moduleMayOnlyDependOnAllowedModules(String module, List<String> allowedModules) {
        Set<String> reachable = new LinkedHashSet<>(allowedModules);
        reachable.add(module);

        String disallowedPackages = MODULE_RULES.stream()
                .map(ModuleRule::module)
                .filter(other -> !reachable.contains(other))
                .map(other -> "com.scholarclaw.modules." + other + "..")
                .collect(Collectors.joining(", "));

        if (disallowedPackages.isEmpty()) {
            return;
        }

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.scholarclaw.modules." + module + "..")
                .should().dependOnClassesThat().resideInAnyPackage(disallowedPackages.split(", "))
                .because("模块 " + module + " 的允许依赖为 " + reachable + "，见 02 §4.3 依赖矩阵");
        rule.check(productionClasses);
    }

    private record ModuleRule(String module, List<String> allowedModules) {
    }
}
