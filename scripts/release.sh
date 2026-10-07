#!/bin/zsh
# Cut a release of this compiler: stamp the version, build it three times, prove the last two agree,
# package the second, and prove the package works from where it was unpacked.
#
#   scripts/release.sh <version> [--library <dir>] [--out <dir>] [--expect <sha>] [--repo <dir>] [-O<level>]
#
# `--repo` names the checkout to release, the one holding this script by default -- a dry run points
# it at a throwaway clone.
#
# **zsh, not sh or bash** -- it is run on the machine that builds the macOS tarball, which is a Mac,
# and its arrays and `${var/old/new}` are zsh's. No variable here is called `path` or `status`.
#
# **It publishes nothing.** No tag, no push, no GitHub release, no formula: the last thing it does is
# print those steps, because each one is seen by other people and is the director's to take once the
# tarball has been looked at. What it does do is commit the version stamp on the local branch.
#
# The steps, in order, each timed into `<out>/release.log`:
#
#   1. refuse unless the tree is clean and HEAD is the commit being released (`origin/dev`, or
#      `--expect <sha>`); a rerun on a HEAD that is already the version stamp over that commit is
#      accepted
#   2. stamp `<version>` into `const Version` (`sh/sysl/compiler/main.sysl`) and `package.version`
#      (`package.hocon`), both under the compiler project -- the test beside `main.sysl` holds the
#      two in step -- and commit it
#   3. stage 1: the `sysl` on PATH (or `SYSL_RELEASE_SEED`) builds this tree against the SEED'S OWN
#      library -- `SYSL_RELEASE_SEED_LIB`, else `<seed prefix>/share/sysl/library` -- since the
#      release library may use forms the seed's library has not got and the seed cannot compile --
#      plus a `--lib` overlay of the modules the release library ADDS (`reference_overlay.sh`), with
#      `SYSL_LIB` unset as the gate runs it
#   4. stage 2: stage 1 builds this tree against the RELEASE library; **stage 2 is what ships**
#   5. stage 3: stage 2 builds this tree against the release library; stage 2 and stage 3 each
#      `emit-llvm .` and the two texts must be identical -- one source, one library, so a difference
#      is stage 2 having miscompiled itself. Stage 1 against stage 2 is logged and does not decide:
#      built over two libraries, the two may differ honestly
#   6. package `sysl-<version>-darwin-arm64.tar.gz`: `bin/sysl` and `share/sysl/library`, which is a
#      prefix (the binary finds its library beside itself), and its sha256
#   7. unpack it to a scratch prefix and, with that prefix's binary and no `SYSL_LIB`: `--version`;
#      build and run a hello program; `sysl test` a small org package; `sysl test <library> --std`
#   8. write a release-notes skeleton into `<out>`, and print the manual steps that remain
#
# **The library** is the in-repo `library/` when this tree has one, and otherwise `--library <dir>`
# or `SYSL_RELEASE_LIBRARY` (the bootstrap's `library/`, or an installed `share/sysl/library`).
#
# **Memory.** A build of this tree peaks at many gigabytes. `SYSL_RELEASE_SLOT=<script>` names a
# wrapper every build and test step is run through, as `zsh <script> <dir> <command...>` -- the
# census's slot script is one -- so a release waits its turn rather than racing a gate.
#
# `-O<level>` is handed to every stage build; without it they take the compiler's own default.
# `--reuse-stage1` keeps a `<out>/stage1` an earlier run left rather than building it again -- the
# seed's build is the slow one -- and says so in the log and the notes.
# `SYSL_RELEASE_PACKAGE` names the package step 7 tests (a git URL or path; `json` by default).

set -u
setopt pipe_fail no_nomatch

die() { print -u2 -r -- "release: $*"; exit 1 }

# --- arguments -----------------------------------------------------------------------------------

version=""
repo=""
reuse_stage1=0
library="${SYSL_RELEASE_LIBRARY:-}"
out=""
expect=""
opt=()

while (( $# > 0 )); do
    case $1 in
        --library) (( $# > 1 )) || die "--library needs a directory"; library=$2; shift 2 ;;
        --out) (( $# > 1 )) || die "--out needs a directory"; out=$2; shift 2 ;;
        --expect) (( $# > 1 )) || die "--expect needs a commit"; expect=$2; shift 2 ;;
        --repo) (( $# > 1 )) || die "--repo needs a checkout"; repo=$2; shift 2 ;;
        --reuse-stage1) reuse_stage1=1; shift ;;
        -O?*) opt=($1); shift ;;
        -*) die "unknown flag '$1'" ;;
        *) [[ -z $version ]] || die "one version, please -- '$version' and '$1'"; version=$1; shift ;;
    esac
done

[[ -n $version ]] || die "usage: scripts/release.sh <version> [--library <dir>] [--out <dir>] [--expect <sha>] [-O<level>]"
[[ $version =~ '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' ]] ||
    die "'$version' is not a version -- three numbers, and an optional '-<pre-release>'"

repo=$(cd "${repo:-${0:A:h}/..}" && git rev-parse --show-toplevel) || die "'${repo:-${0:A:h}/..}' is not inside a git checkout"
# **Two layouts, told apart by one file.** Where `compiler/package.hocon` exists the compiler is a
# project of its own under `compiler/`, beside `library/`, `docs/` and `scripts/`; otherwise the
# repository root is the compiler project. Everything that builds, emits or stamps uses `proj`.
if [[ -f $repo/compiler/package.hocon ]]; then
    proj=$repo/compiler
else
    proj=$repo
fi

out=${out:-$repo/release-out/$version}
mkdir -p $out || die "cannot create $out"
out=${out:A}
log=$out/release.log
: > $log

seed=${SYSL_RELEASE_SEED:-$(command -v sysl)}
[[ -x $seed ]] || die "no seed compiler: put a 'sysl' on PATH or set SYSL_RELEASE_SEED"

# The library the seed was released with, which is the only one it is known to compile against.
# `:A` follows a brew symlink into the keg, where `share/sysl/library` sits beside `bin/sysl`.
seed_lib=${SYSL_RELEASE_SEED_LIB:-${seed:A:h:h}/share/sysl/library}
[[ -d $seed_lib/sysl ]] ||
    die "no library for the seed $seed: '$seed_lib' is not one -- set SYSL_RELEASE_SEED_LIB to the library that seed was released with"
seed_lib=${seed_lib:A}

slot=${SYSL_RELEASE_SLOT:-}
[[ -z $slot || -f $slot ]] || die "SYSL_RELEASE_SLOT names '$slot', which is not a file"

asset=sysl-$version-darwin-arm64.tar.gz
tarball=$out/$asset

say() { print -r -- "$*" | tee -a $log }

mmss() { printf '%02d:%02d' $(( $1 / 60 )) $(( $1 % 60 )) }

# One timed step. Its output goes to `<out>/<name>.log`, and a failure names that file.
took=()
step() {
    local name=$1; shift
    local began=$SECONDS
    say "== $name"
    if ! "$@" > $out/$name.log 2>&1; then
        say "FAILED $name after $(mmss $(( SECONDS - began ))) -- see $out/$name.log"
        tail -20 $out/$name.log | tee -a $log
        exit 1
    fi
    local spent=$(mmss $(( SECONDS - began )))
    took+=("$name $spent")
    say "ok $name ($spent)"
}

# A build or a test, run in a directory, through the slot wrapper when there is one.
heavy() {
    local dir=$1; shift
    if [[ -n $slot ]]; then
        zsh $slot $dir "$@"
    else
        (cd $dir && "$@")
    fi
}

# --- 1. the tree ---------------------------------------------------------------------------------

check_tree() {
    local dirty
    dirty=$(git -C $repo status --porcelain) || return 1
    if [[ -n $dirty ]]; then
        print -r -- "the tree is not clean:"
        print -r -- $dirty
        return 1
    fi

    local base=${expect:-origin/dev}
    local want head
    want=$(git -C $repo rev-parse --verify "$base^{commit}") || { print "cannot read '$base'"; return 1 }
    head=$(git -C $repo rev-parse HEAD)

    if [[ $head == $want ]]; then
        print -r -- "HEAD $head is $base"
        return 0
    fi

    # A rerun: HEAD is this version's stamp, one commit over the commit being released.
    if [[ $(git -C $repo rev-parse HEAD^) == $want ]] &&
       [[ $(git -C $repo log -1 --format=%s) == "sysl $version" ]]; then
        print -r -- "HEAD $head is the 'sysl $version' stamp over $base ($want)"
        return 0
    fi

    print -r -- "HEAD $head is not $base ($want) -- release the commit you mean, or pass --expect"
    return 1
}

step 01-tree check_tree
base_commit=$(git -C $repo rev-parse ${expect:-origin/dev})

# --- the library ---------------------------------------------------------------------------------

if [[ -d $repo/library/sysl ]]; then
    library=$repo/library
elif [[ -z $library ]]; then
    die "this tree has no library/ -- pass --library <dir> or set SYSL_RELEASE_LIBRARY"
fi
library=${library:A}
[[ -d $library/sysl ]] || die "'$library' is not a standard library (no sysl/ inside it)"
say "library: $library"

library_from="$library"
if git -C $library rev-parse --git-dir > /dev/null 2>&1; then
    # The tarball copies the directory as it stands, so it has to be what its commit says it is.
    [[ -z $(git -C $library status --porcelain -- .) ]] ||
        die "the library at $library has uncommitted or untracked files, and the tarball would ship them"
    library_from="$library at $(git -C $library describe --tags --always --dirty 2>/dev/null) ($(git -C $library rev-parse --short HEAD))"
fi

# --- 2. the version ------------------------------------------------------------------------------

main_file=$proj/sh/sysl/compiler/main.sysl
manifest=$proj/package.hocon
rel=${proj#$repo}
rel=${rel#/}
rel=${rel:+$rel/}

stamp() {
    local text old new

    text=$(< $main_file)
    old=$(print -r -- $text | grep -m1 '^const Version: string = "') || { print "no 'const Version' in $main_file"; return 1 }
    new="const Version: string = \"$version\""
    [[ $old == $new ]] || print -r -- "${text/$old/$new}" > $main_file

    text=$(< $manifest)
    old=$(print -r -- $text | grep -m1 '^  version = "') || { print "no package.version in $manifest"; return 1 }
    new="  version = \"$version\""
    [[ $old == $new ]] || print -r -- "${text/$old/$new}" > $manifest

    grep -qxF "const Version: string = \"$version\"" $main_file || { print "the stamp did not take in $main_file"; return 1 }
    grep -qxF "  version = \"$version\"" $manifest || { print "the stamp did not take in $manifest"; return 1 }

    if [[ -z $(git -C $repo status --porcelain) ]]; then
        print -r -- "already stamped $version"
        return 0
    fi

    git -C $repo diff --stat
    local changed
    changed=$(git -C $repo diff --name-only | sort | tr '\n' ' ')
    [[ $changed == "${rel}package.hocon ${rel}sh/sysl/compiler/main.sysl " ]] || { print "the stamp touched $changed"; return 1 }

    git -C $repo commit -q -am "sysl $version" || return 1
    git -C $repo log -1 --oneline
}

step 02-version stamp
release_commit=$(git -C $repo rev-parse HEAD)
say "release commit: $release_commit"

# --- 3-5. the stages -----------------------------------------------------------------------------

seed_version=$($seed --version 2>&1 | head -1)
say "seed: $seed ($seed_version)"
say "seed library: $seed_lib"
say "compiler project: $proj"

# Stage 1: the seed, against its own library, plus a `--lib` overlay of the modules the release
# library ADDS (`scripts/reference_overlay.sh`; today `sysl.testing`, which the tree's tests import).
# The release library itself is out of the seed's reach -- its changed modules use forms only this
# compiler has -- and without the overlay the tree's imports of the added modules name nothing. An
# empty overlay (a seed whose library already has them all) is left off the command line.
build_stage1() {
    local ov
    ov=$(sh $repo/scripts/reference_overlay.sh $seed_lib $out/seed-overlay) ||
        { print "the seed overlay could not be made"; return 1 }
    local lib_args=()
    if [[ -n $(ls -A $ov) ]]; then
        lib_args=(--lib $ov)
        print -r -- "seed overlay: $ov -- $(cd $ov && find . -type f | sort | tr '\n' ' ')"
    else
        print -r -- "seed overlay: none (the seed's library has every module the tree adds)"
    fi
    # The gate's form: `SYSL_LIB` unset, so the seed finds the library beside itself. Only a library
    # named outright (`SYSL_RELEASE_SEED_LIB`, a seed with none beside it) is handed over.
    local lib_env=(-u SYSL_LIB)
    [[ -n ${SYSL_RELEASE_SEED_LIB:-} ]] && lib_env=(SYSL_LIB=$seed_lib)
    heavy $proj env $lib_env $seed build . $lib_args $opt -o $out/stage1
}

if (( reuse_stage1 )); then
    [[ -x $out/stage1 ]] || die "--reuse-stage1, but there is no $out/stage1"
    say "== 03-stage1 reused: $out/stage1 ($(stat -f %Sm $out/stage1))"
    took+=("03-stage1 reused")
else
    step 03-stage1 build_stage1
fi
step 04-stage2 heavy $proj env SYSL_LIB=$library $out/stage1 build . $opt -o $out/stage2
step 05a-stage3 heavy $proj env SYSL_LIB=$library $out/stage2 build . $opt -o $out/stage3

# `emit-llvm .` from one stage against the release library, into `<out>/<stage>.ll`, the slot
# wrapper's own line dropped so the text is the compiler's.
emitted() {
    heavy $proj env SYSL_LIB=$library $out/$1 emit-llvm . > $out/$1.ll || return 1
    grep -v '^slot .* taken ' $out/$1.ll > $out/$1.ll.tmp && mv $out/$1.ll.tmp $out/$1.ll
    [[ -s $out/$1.ll ]]
}

agree() {
    emitted stage2 || { print "stage 2 emit-llvm failed"; return 1 }
    emitted stage3 || { print "stage 3 emit-llvm failed"; return 1 }

    if ! cmp -s $out/stage2.ll $out/stage3.ll; then
        print "stage 2 and stage 3 emit different text for this tree:"
        diff $out/stage2.ll $out/stage3.ll | head -40
        return 1
    fi
    print -r -- "identical: $(wc -l < $out/stage2.ll | tr -d ' ') lines, $(wc -c < $out/stage2.ll | tr -d ' ') bytes"
}

step 05b-stages-agree agree

# Stage 1 against stage 2, for the record only: built over two libraries they may differ honestly.
if emitted stage1 > /dev/null 2>&1; then
    if cmp -s $out/stage1.ll $out/stage2.ll; then
        stage12="identical"
    else
        stage12="different ($(diff $out/stage1.ll $out/stage2.ll | grep -c '^[<>]') changed lines)"
    fi
else
    stage12="stage 1 could not emit against the release library"
fi
say "report only -- stage 1 vs stage 2 emit-llvm: $stage12"

[[ $($out/stage2 --version) == "sysl $version" ]] || die "stage 2 answers '$($out/stage2 --version)', not 'sysl $version'"

# --- 6. the tarball ------------------------------------------------------------------------------

package() {
    local stage=$out/stage
    rm -rf $stage $tarball
    mkdir -p $stage/bin $stage/share/sysl || return 1
    cp $out/stage2 $stage/bin/sysl || return 1
    cp -R $library $stage/share/sysl/library || return 1

    # No AppleDouble files: `-C stage .` makes the tarball a prefix, and brew installs all of it.
    COPYFILE_DISABLE=1 tar -czf $tarball -C $stage . || return 1
    (cd $out && shasum -a 256 $asset > $asset.sha256) || return 1

    tar -tzf $tarball | grep -v '^\./share/sysl/library/.'
    otool -L $stage/bin/sysl
    cat $out/$asset.sha256
}

step 06-package package
sha=$(cut -d' ' -f1 $out/$asset.sha256)
size=$(stat -f %z $tarball)

# --- 7. from the tarball, not the tree -----------------------------------------------------------

prefix=$out/prefix
work=$out/verify
package_src=${SYSL_RELEASE_PACKAGE:-}
if [[ -z $package_src ]]; then
    if [[ -d $HOME/dev/sysl-lang/json/.git ]]; then package_src=$HOME/dev/sysl-lang/json
    else package_src=https://github.com/sysl-lang/json; fi
fi

unpack() {
    rm -rf $prefix $work
    mkdir -p $prefix $work || return 1
    tar -xzf $tarball -C $prefix || return 1
    [[ -x $prefix/bin/sysl && -d $prefix/share/sysl/library/sysl ]] || { print "the tarball is not a prefix"; return 1 }
    ls -la $prefix $prefix/bin $prefix/share/sysl
}

version_check() {
    local said
    said=$(cd $work && env -u SYSL_LIB $prefix/bin/sysl --version) || return 1
    print -r -- $said
    [[ $said == "sysl $version" ]]
}

hello() {
    mkdir -p $work/hello || return 1
    print -r -- 'print("Hello, sysl!")
print(6 * 7)' > $work/hello/hello.sysl

    heavy $work/hello env -u SYSL_LIB $prefix/bin/sysl build hello.sysl -o hello || return 1
    local said
    said=$($work/hello/hello) || return 1
    print -r -- $said
    [[ $said == $'Hello, sysl!\n42' ]] || { print "the built program printed the wrong thing"; return 1 }

    said=$(heavy $work/hello env -u SYSL_LIB $prefix/bin/sysl run hello.sysl | grep -v '^slot .* taken ') || return 1
    print -r -- $said
    [[ $said == $'Hello, sysl!\n42' ]] || { print "'sysl run' printed the wrong thing"; return 1 }
}

# A run's summary is `N passed, M failed[, ...] — Tms`; any failure, or no summary, is a failure.
suite_passed() {
    local summary
    summary=$(grep -E '^[0-9]+ passed, [0-9]+ failed' $1 | tail -1)
    [[ -n $summary ]] || { print "no summary line in $1"; return 1 }
    print -r -- $summary
    [[ $summary == *" 0 failed"* ]]
}

org_package() {
    git clone --quiet $package_src $work/package || return 1
    print -r -- "testing $package_src at $(git -C $work/package rev-parse --short HEAD)"
    heavy $work/package env -u SYSL_LIB $prefix/bin/sysl test . > $out/package-test.out 2>&1
    local rc=$?
    tail -5 $out/package-test.out
    (( rc == 0 )) && suite_passed $out/package-test.out
}

std_suite() {
    heavy $work env -u SYSL_LIB $prefix/bin/sysl test $prefix/share/sysl/library --std > $out/std-test.out 2>&1
    local rc=$?
    tail -5 $out/std-test.out
    (( rc == 0 )) && suite_passed $out/std-test.out
}

step 07a-unpack unpack
step 07b-version version_check
step 07c-hello hello
step 07d-package-test org_package
step 07e-std-test std_suite

package_summary=$(grep -E '^[0-9]+ passed' $out/package-test.out | tail -1)
std_summary=$(grep -E '^[0-9]+ passed' $out/std-test.out | tail -1)

# --- 8. the notes, and what is left --------------------------------------------------------------

notes=$out/release-notes.md
cat > $notes <<NOTES
# sysl $version — <HEADLINE: one line>

<!-- A skeleton written by scripts/release.sh. Fill in every <...>, delete what does not apply. -->

<ONE PARAGRAPH: what this release is for. For 0.1.0-alpha.1: the self-hosted compiler, written in
sysl, replaces the Scala bootstrap as \`sysl\`.>

## Install

\`\`\`
brew install sysl-lang/tap/<sysl or sysl-alpha>
\`\`\`

or download \`$asset\` below and put its \`bin/\` on your PATH; the compiler finds
\`share/sysl/library\` beside itself.

## Behaviour changes (read these first)

- <anything that now accepts or answers differently from bootstrap 0.0.162>

## What changed versus bootstrap 0.0.162

- **The compiler is written in sysl**, built by itself: stage 1 (built by $seed_version against
  its own library) built stage 2, the binary in this tarball, against the release library; stage 2
  rebuilt itself as stage 3, and stage 2 and stage 3 emit identical LLVM text for the compiler's own tree.
- **\`sysl doc\` is built in** and renders Markdown; there is no separate \`sysl-doc\` binary.
- <features>

## Known divergences from the bootstrap

- <from CLAUDE.md § Open divergences: diagnostic spans/carets anchor at the extent, not the reference's anchor>
- \`--lib\` takes a source directory; a \`.syslib\` and \`--std-lib\` are refused by name, and \`--ar\` is not built.
- \`dev_dependencies\` are read and refused but not resolved.
- <a package's \`sysl = "x.y.z"\` floor: \`OnTheReleaseLine\` in manifest.sysl decides whether this version is compared against it>
- <the \`prove\` command is not built>

## Platforms

- **macOS arm64 only.** Linux (x86_64, arm64) and macOS x86_64 are not built for this alpha; the
  bootstrap's Linux tarballs came from \`.github/workflows/release-linux.yml\` on CI runners, and
  this compiler has no such workflow yet.

## Verified, from the tarball

| check | result |
|---|---|
| \`sysl --version\` | \`sysl $version\` |
| hello, built and run | \`Hello, sysl!\` / \`42\` |
| \`sysl test\` on $package_src | $package_summary |
| \`sysl test share/sysl/library --std\` | $std_summary |
| stage 2 = stage 3 (\`emit-llvm .\`) | identical |
| stage 1 vs stage 2 (report only) | $stage12 |

## Provenance

- commit: \`$release_commit\` (stamp over \`$base_commit\`)
- seed compiler: \`$seed\` — $seed_version, against its library \`$seed_lib\`$( (( reuse_stage1 )) && print -n ' (stage 1 reused from an earlier run)')
- library: $library_from
- \`$asset\`: $size bytes, sha256 \`$sha\`
NOTES

say ""
say "== done: $tarball"
say "   size $size bytes, sha256 $sha"
for t in $took; say "   $t"
say "   notes skeleton: $notes"

cat <<STEPS | tee -a $log

== What is left, and NOT done by this script ==

1. Push the stamp and tag it:
     git -C $repo push origin HEAD:dev
     git -C $repo tag -a v$version $release_commit -m "sysl $version"
     git -C $repo push origin v$version

2. The GitHub release (fill in $notes first):
     gh release create v$version --repo sysl-lang/sysl --prerelease --title "sysl $version — <headline>" --notes-file $notes $tarball

3. Hash the asset the release URL serves, not the local file:
     curl -sL -o $out/dl.tar.gz https://github.com/sysl-lang/sysl/releases/download/v$version/$asset
     shasum -a 256 $out/dl.tar.gz
   It must print $sha.

4. The formula, in ~/dev/sysl-lang/homebrew-tap/Formula: version "$version",
     url "https://github.com/sysl-lang/sysl/releases/download/v#{version}/$asset" (note: sysl-lang/sysl, not sysl-bootstrap)
     sha256 "$sha"
   then commit and push the tap.

5. Install and test it as work:
     sudo -n -u work -H /opt/homebrew/bin/brew update
     sudo -n -u work -H /opt/homebrew/bin/brew install sysl-lang/tap/<formula>
     sudo -n -u work -H /opt/homebrew/bin/brew test <formula>

6. The org sweep, with the tarball's own binary:
     SYSL_LIB=$prefix/share/sysl/library $repo/scripts/org_build_census.sh $prefix/bin/sysl
STEPS
