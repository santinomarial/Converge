package io.converge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithArchitectureTest {

    @Test
    void moduleBoundariesAreValid() {
        ApplicationModules.of(ConvergeApplication.class).verify();
    }

    @Test
    void architectureDocumentationIsGeneratedFromTheCode() {
        var modules = ApplicationModules.of(ConvergeApplication.class);
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
