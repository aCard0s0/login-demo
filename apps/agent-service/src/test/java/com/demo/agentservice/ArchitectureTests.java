package com.demo.agentservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The package rules the README states, enforced: {@code mcp} depends on {@code agent} and {@code activity};
 * {@code agent} on {@code activity} and {@code token}; nothing points back. Inside {@code agent}, {@code dto}
 * may import {@code entities}, never the other way round. Only the services touch a repository.
 */
@AnalyzeClasses(packages = "com.demo.agentservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    static final String ACTIVITY = "com.demo.agentservice.activity..";
    static final String AGENT = "com.demo.agentservice.agent..";
    static final String MCP = "com.demo.agentservice.mcp..";
    static final String TOKEN = "com.demo.agentservice.token..";
    static final String STATS = "com.demo.agentservice.stats..";
    static final String SUPPORT = "com.demo.agentservice.support..";

    @ArchTest
    static final ArchRule activityDependsOnNothingHere = noClasses().that().resideInAPackage(ACTIVITY)
            .should().dependOnClassesThat().resideInAnyPackage(AGENT, MCP, TOKEN, STATS, SUPPORT);

    @ArchTest
    static final ArchRule tokenDependsOnNothingHere = noClasses().that().resideInAPackage(TOKEN)
            .should().dependOnClassesThat().resideInAnyPackage(AGENT, MCP, ACTIVITY, STATS, SUPPORT);

    @ArchTest
    static final ArchRule nothingPointsBackAtMcp = noClasses().that().resideOutsideOfPackage(MCP)
            .should().dependOnClassesThat().resideInAPackage(MCP);

    @ArchTest
    static final ArchRule agentDoesNotReachForward = noClasses().that().resideInAPackage(AGENT)
            .should().dependOnClassesThat().resideInAnyPackage(STATS, SUPPORT);

    @ArchTest
    static final ArchRule entitiesNeverImportDtos = noClasses().that().resideInAPackage("com.demo.agentservice.agent.entities..")
            .should().dependOnClassesThat().resideInAPackage("com.demo.agentservice.agent.dto..");

    @ArchTest
    static final ArchRule onlyTheServicesTouchARepository = noClasses().that().resideOutsideOfPackages(AGENT, ACTIVITY)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule controllersStayOutOfTheMcpAndTheMcpOutOfTheControllers = noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().resideInAPackage(MCP);
}
