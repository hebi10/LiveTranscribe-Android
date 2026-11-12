import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).parents[1] / 'source.py'
spec = importlib.util.spec_from_file_location('source', SCRIPT)
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)
SHA = 'a' * 40
REPO = 'hebi10/LiveTranscribe-Android'


class SourceTests(unittest.TestCase):
    def test_initialize_mode_requires_explicit_manual_input_or_trusted_main_subject_marker(self):
        manual = {'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': SHA}
        self.assertEqual(source.release_mode(dict(manual, PLAY_REQUESTED_MODE='initialize'), {}, 'ordinary'), 'initialize')
        self.assertEqual(source.release_mode(dict(manual, PLAY_REQUESTED_MODE='check'), {}, '[initialize-play]'), 'check')
        run = {'name': 'Private Android APK', 'path': '.github/workflows/build.yml', 'event': 'push', 'conclusion': 'success', 'head_branch': 'main', 'head_sha': SHA, 'head_repository': {'full_name': REPO}}
        env = {'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_run'}
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Initial upload [initialize-play] (#20)'), 'initialize')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Promote [promote-play-alpha-22] (#50)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Play: alpha v22 draft commit [commit-play-alpha-draft-v22]'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Play: alpha v22 draft commit [commit-play-alpha-draft-v22]: accidental suffix'), 'deploy')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Store imagery [sync-play-images] (#37)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'QA: recapture current UI [ui-gap-00-capture] (#53)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Docs: CAPTION FRAME v1 [ui-gap-01-design] (#54)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'UI: compact home [ui-gap-02-home] (#55)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'UI: model + permission copy [ui-gap-03-setup] (#56)'), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Fake [ui-gap-03-setup]-suffix'), 'deploy')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Fake [ui-gap-02-home]-suffix'), 'deploy')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Fake [ui-gap-01-design]-suffix'), 'deploy')
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, 'Fake [ui-gap-00-capture]-suffix'), 'deploy')
        for subject in ('ordinary', 'ordinary\n[initialize-play]', '[initialize-play]-example', 'x[initialize-play]'):
            self.assertEqual(source.release_mode(env, {'workflow_run': run}, subject), 'deploy')
        for key, value in (('event', 'pull_request'), ('head_branch', 'feature'), ('conclusion', 'failure'), ('head_repository', {'full_name': 'attacker/fork'})):
            with self.assertRaises(source.SourceError):
                source.release_mode(env, {'workflow_run': dict(run, **{key: value})}, '[initialize-play]')
        for ref in ('refs/tags/v1', 'refs/heads/feature'):
            with self.assertRaises(source.SourceError):
                source.release_mode(dict(manual, GITHUB_REF=ref, PLAY_REQUESTED_MODE='initialize'), {}, '[initialize-play]')

    def test_release_01b_readonly_inspection_marker_cannot_upload_aab(self):
        run = {'name': 'Private Android APK', 'path': '.github/workflows/build.yml',
               'event': 'push', 'conclusion': 'success', 'head_branch': 'main',
               'head_sha': SHA, 'head_repository': {'full_name': REPO}}
        env = {'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_run'}
        subject = 'Release: Alpha track inspect [release-01-b-check]'
        self.assertEqual(source.release_mode(env, {'workflow_run': run}, subject), 'check')
        self.assertEqual(source.release_mode(env, {'workflow_run': run},
                                             subject + ' unapproved suffix'), 'deploy')

    def test_manual_dispatch_only_accepts_main(self):
        for ref in ('refs/heads/feature', 'refs/tags/v1'):
            with self.assertRaises(source.SourceError):
                source.resolve({'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': ref, 'GITHUB_SHA': SHA}, {})
        self.assertEqual(source.resolve({'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': SHA}, {}), SHA)

    def test_automatic_source_must_be_successful_same_repo_main_push(self):
        run = {'name': 'Private Android APK', 'path': '.github/workflows/build.yml', 'event': 'push', 'conclusion': 'success',
               'head_branch': 'main', 'head_sha': SHA, 'head_repository': {'full_name': REPO}}
        env = {'GITHUB_REPOSITORY': REPO, 'GITHUB_EVENT_NAME': 'workflow_run'}
        self.assertEqual(source.resolve(env, {'workflow_run': run}), SHA)
        for key, value in [('event', 'pull_request'), ('head_branch', 'feature'), ('conclusion', 'failure'), ('path', 'other.yml')]:
            invalid = dict(run, **{key: value})
            with self.assertRaises(source.SourceError):
                source.resolve(env, {'workflow_run': invalid})
        run['head_repository'] = {'full_name': 'attacker/fork'}
        with self.assertRaises(source.SourceError):
            source.resolve(env, {'workflow_run': run})

    def test_stale_main_source_fails_before_deployment(self):
        source.require_current_main(SHA, SHA)
        with self.assertRaisesRegex(source.SourceError, 'stale'):
            source.require_current_main(SHA, 'b' * 40)

    def test_deploy_requires_both_completed_verify_jobs_on_exact_sha(self):
        run = {'head_sha': SHA, 'head_branch': 'main', 'event': 'push', 'conclusion': 'success', 'path': '.github/workflows/build.yml'}
        jobs = [{'name': 'build', 'conclusion': 'success'}]
        source.verify_ci(SHA, run, jobs)
        for invalid_jobs in ([], [dict(jobs[0], conclusion='skipped')]):
            with self.assertRaises(source.SourceError):
                source.verify_ci(SHA, run, invalid_jobs)
        with self.assertRaises(source.SourceError):
            source.verify_ci('b' * 40, run, jobs)


if __name__ == '__main__':
    unittest.main()
