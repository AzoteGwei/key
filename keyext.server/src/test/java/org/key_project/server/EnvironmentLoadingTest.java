/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.util.List;

import de.uka.ilkd.key.proof.io.AbstractProblemLoader;
import de.uka.ilkd.key.speclang.Contract;

import org.key_project.util.collection.ImmutableSet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that loading produces a genuinely usable environment.
 *
 * <p>
 * Asserting only that loading "did not throw" would be exactly the mistake this project is built to
 * avoid: KeY can return something for an input it did not really understand, and that something
 * reads a lot like success. So this asserts on what the environment actually contains.
 */
class EnvironmentLoadingTest {

    private static final Path FIXTURE =
        Path.of("src/test/resources/fixtures/adder").toAbsolutePath();

    @Test
    void aLoadedProjectExposesTheContractsItsSpecificationDeclares() throws Exception {
        ServerUserInterfaceControl control = new ServerUserInterfaceControl();

        AbstractProblemLoader loader =
            control.load(null, FIXTURE, List.of(), null, List.of(), null, false, null);

        ImmutableSet<Contract> contracts =
            loader.getInitConfig().getServices().getSpecificationRepository().getAllContracts();

        assertThat(contracts).isNotEmpty();
        assertThat(contracts).anySatisfy(contract -> {
            assertThat(contract.getName()).contains("Adder");
            assertThat(contract.getName()).contains("add");
        });

        // The contract name is what the protocol uses as contractId, so it must round-trip.
        Contract any = contracts.iterator().next();
        assertThat(loader.getInitConfig().getServices().getSpecificationRepository()
                .getContractByName(any.getName())).isSameAs(any);
    }
}
