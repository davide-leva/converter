#!/usr/bin/env python3
"""Versioning tests in disposable repositories, including real local pushes."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parent.parent

class VersioningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='converter-version-test-')
        self.addCleanup(self.temp.cleanup)
        self.repo = pathlib.Path(self.temp.name) / 'repo'
        self.repo.mkdir()
        for name in ('VERSION','pom.xml','package.json','package-lock.json',
                     'scripts/version.py','scripts/bump.sh','scripts/check-version-bump.py',
                     'scripts/check-version-bump.sh','.githooks/pre-push'):
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / name, target)
        self.run_cmd('git','init','-b','main')
        self.run_cmd('python3','scripts/version.py','sync','1.0.0')
        self.run_cmd('git','config','user.name','Version Test')
        self.run_cmd('git','config','user.email','version@example.test')
        self.run_cmd('git','config','core.hooksPath','.githooks')
        source = self.repo / 'src/main/resources/static/app.js'
        source.parent.mkdir(parents=True)
        source.write_text('initial code\n')
        self.commit('initial')
        self.initial = self.run_cmd('git','rev-parse','HEAD').stdout.strip()
        self.remote = pathlib.Path(self.temp.name) / 'remote.git'
        self.run_cmd('git','init','--bare',str(self.remote))
        self.run_cmd('git','remote','add','origin',str(self.remote))

    def run_cmd(self,*args,ok=True):
        result = subprocess.run(args,cwd=self.repo,text=True,input='',capture_output=True)
        if ok:
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        else:
            self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        return result

    def commit(self,message):
        self.run_cmd('git','add','.')
        self.run_cmd('git','commit','-m',message)

    def change(self):
        path=self.repo/'src/main/resources/static/app.js'
        path.write_text(path.read_text()+'changed\n')
        self.commit('change production code')

    def test_real_push_initial_bump_and_new_branch(self):
        self.run_cmd('git','push','origin','HEAD:refs/heads/main')
        self.change()
        self.run_cmd('git','push','origin','HEAD:refs/heads/main',ok=False)
        self.run_cmd('bash','scripts/bump.sh','patch','--no-commit')
        # A bump in the working tree must not authorize an unbumped committed push.
        self.run_cmd('git','push','origin','HEAD:refs/heads/main',ok=False)
        self.commit('release patch')
        self.run_cmd('git','push','origin','HEAD:refs/heads/main')
        self.assertEqual((self.repo/'VERSION').read_text().strip(),'1.0.1')
        self.run_cmd('git','checkout','-b','feature')
        self.change()
        self.run_cmd('git','push','origin','HEAD:refs/heads/feature',ok=False)
        self.run_cmd('bash','scripts/bump.sh','minor','--commit')
        self.run_cmd('git','push','origin','HEAD:refs/heads/feature')
        self.assertEqual((self.repo/'VERSION').read_text().strip(),'1.1.0')
        self.run_cmd('git','push','origin',':refs/heads/feature')

    def test_docs_and_tests_do_not_require_bump(self):
        self.run_cmd('git','push','origin','HEAD:refs/heads/main')
        (self.repo/'README.md').write_text('documentation\n')
        path=self.repo/'src/test/java/Test.java'
        path.parent.mkdir(parents=True)
        path.write_text('test\n')
        self.commit('docs and tests')
        self.run_cmd('git','push','origin','HEAD:refs/heads/main')

    def test_regression_and_unsynchronized_commits_are_rejected(self):
        self.run_cmd('git','push','origin','HEAD:refs/heads/main')
        data=json.loads((self.repo/'package.json').read_text())
        data['version']='9.0.0'
        (self.repo/'package.json').write_text(json.dumps(data))
        self.commit('bad manifest')
        self.run_cmd('git','push','origin','HEAD:refs/heads/main',ok=False)
        self.run_cmd('git','reset','--hard',self.initial)
        self.run_cmd('python3','scripts/version.py','sync','0.9.0')
        self.commit('regression')
        self.run_cmd('git','push','origin','HEAD:refs/heads/main',ok=False)

    def test_default_range_and_major_bump(self):
        self.change()
        self.run_cmd('bash','scripts/check-version-bump.sh',ok=False)
        self.run_cmd('bash','scripts/bump.sh','major','--commit')
        self.run_cmd('bash','scripts/check-version-bump.sh')
        self.assertEqual((self.repo/'VERSION').read_text().strip(),'2.0.0')
        self.run_cmd('git','tag','v2.0.0')
        self.run_cmd('bash','scripts/check-version-bump.sh')
        self.run_cmd('bash','scripts/bump.sh','invalid','--no-commit',ok=False)

if __name__=='__main__':
    unittest.main()
