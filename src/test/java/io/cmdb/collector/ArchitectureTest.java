package io.cmdb.collector;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.jmolecules.archunit.JMoleculesArchitectureRules;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

  @Test
  void onionLayersAreRespected() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.cmdb.collector");
    JMoleculesArchitectureRules.ensureOnionClassical().check(classes);
  }

  @Test
  void moduleBoundariesAreRespected() {
    ApplicationModules.of(CollectorApplication.class).verify();
  }
}
