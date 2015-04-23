#!/usr/bin/env python
# coding=utf-8

# configure.coqoon.py, a configure script for Coqoon projects
# A component of Coqoon, an integrated development environment for Coq proofs
# Copyright © 2014, 2015 Alexander Faithfull
#
# This script is free software; its author grants unlimited permission to use,
# copy, modify and/or redistribute it.

# Manipulating this project using Coqoon may cause this file to be overwritten
# without warning: any local changes you may have made will not be preserved.

_configure_coqoon_version = 7

import io, os, re, sys, shlex, codecs
from argparse import ArgumentParser

parser = ArgumentParser(
    description = "Generate a site-specific Makefile to compile this " +
                  "Coqoon project.")
parser.add_argument(
    "vars",
    metavar = "NAME=VALUE",
    help = "the name and value for a variable specifying the path to an " +
           "external dependency",
    nargs = '*')
parser.add_argument(
    "-v", "--version",
    action = "version",
    version = "%(prog)s v" + str(_configure_coqoon_version))
parser.add_argument(
    "-p", "--prompt",
    action = "store_true",
    dest = "prompt",
    help = "prompt the user to specify values for any missing variables")
parser.add_argument(
    "-k", "--keep-going",
    action = "store_true",
    dest = "persevere",
    help = "generate a Makefile even if some dependencies could not be " +
           "resolved")
parser.add_argument(
    "-Q", "--use-Q",
    action = "store_true",
    dest = "use_q",
    help = "use the -Q option to pass load path information to Coq (version " +
           "8.5 or later)")

args = parser.parse_args()

def warn(warning):
    sys.stderr.write("%s: warning: %s\n" % (parser.prog, warning))

def err(error, usage = True):
    prog = parser.prog
    sys.stderr.write("%s: error: %s\n" % (prog, error))
    if usage:
        sys.stderr.write("Try \"%s --help\" for more information.\n" % prog)
    sys.exit(1)

def striplist(l):
    return map(lambda s: s.rstrip("/"), l)

# This utility class is modelled loosely upon org.eclipse.core.runtime.Path,
# although is nowhere near as complicated
class Path:
    def __init__(self, i = None):
        if i != None:
            self._bits = striplist(i.split("/"))
        else:
            self._bits = []

    def bit(self, i):
        return self._bits[i]
    def head(self):
        return self._bits[0]
    def tail(self):
        return self.drop_first(1)

    def first(self):
        return self.head()
    def last(self):
        return self._bits[len(self) - 1]

    def drop_first(self, i):
        p = Path()
        p._bits.extend(self._bits[i:])
        return p
    def drop_last(self, i):
        p = Path()
        p._bits.extend(self._bits[:i])
        return p

    def replace(self, i, s):
        p = Path()
        p._bits.extend(self._bits)
        p._bits[i] = s.rstrip("/")
        return p

    def __len__(self):
        return len(self._bits)

    def append(self, i):
        if len(i) != 0:
            p = Path()
            p._bits.extend(self._bits)
            p._bits.append(i.rstrip("/"))
            return p
        else:
            return self
    def append_path(self, i):
        if len(i._bits) != 0:
            p = Path()
            p._bits.extend(self._bits)
            p._bits.extend(i._bits)
            return p
        else:
            return self

    def isdir(self):
        return os.path.isdir(str(self))
    def isfile(self):
        return os.path.isdir(str(self))

    # Convenience file operations
    def open(self, mode = "r", encoding = "utf_8"):
        return io.open(str(self), mode = mode, encoding = encoding)
    def utime(self, times):
        os.utime(str(self), times)

    def __iter__(self):
        return self._bits.__iter__()

    def __str__(self):
        return "/".join(self)

    @staticmethod
    def cwd():
        return Path(os.getcwd())

variables = {} # Variable name -> user-specified value for variable
for i in args.vars:
    match = re.match("^(\w+)=(.*)$", i, 0)
    if match:
        (var, value) = match.groups()
        variables[var] = value

def prompt_for(vn, prompt, default = None):
    if vn in variables:
        return variables[vn]
    elif not args.prompt:
        return default
    print prompt
    try:
        pn = None
        if default == None:
            pn = "%s: " % vn
        else:
            pn = "%s [%s]: " % (vn, default)
        val = raw_input(pn)
        if len(val) > 0:
            return val
    except EOFError:
        pass
    return default

doomed = False

def load_coq_project_configuration(cwd, path):
    configuration = []
    # When cwd is none, all the relative paths in @configuration will remain
    # relative; note that this is only ever tolerable for paths known to be
    # within this project
    if cwd == None:
        cwd = Path(None)
    default_output = str(cwd.append("bin"))
    try:
        # We don't care whether @path is absolute or relative as long as we can
        # open it, but the paths that we read from it are guaranteed to be
        # relative and we need them to be absolute
        with io.open(path, mode = "r", encoding = "utf_8") as file:
            for line in filter(lambda l: l.startswith("KOPITIAM_"), file):
                split_line = shlex.split(line)
                num = split_line[0][len("KOPITIAM_"):]
                lp = shlex.split(split_line[2])
                configuration.append(lp)

                if lp[0] == "SourceLoadPath":
                    lp[1] = str(cwd.append(lp[1]))
                    if len(lp) > 2:
                        lp[2] = str(cwd.append(lp[2]))
                elif lp[0] == "DefaultOutput":
                    lp[1] = str(cwd.append(lp[1]))
                    default_output = lp[1]
                elif lp[0] == "ExternalLoadPath":
                    if os.path.isdir(lp[1]):
                        pass
                    else:
                        elp_name = lp[2] if len(lp) > 2 else "(unknown)"
                        # Deriving the variable name from the position in the
                        # _CoqProject file is hardly ideal, but we don't have
                        # much else to go on for external load paths...
                        lp[1] = prompt_for("EXT_" + num, """\
Specify the path to the \"%s\" library.""" % elp_name, lp[1])
                        if not os.path.isdir(lp[1]):
                            warn("""\
the library "%s" (EXT_%s) could not be found; dependencies on it will not be \
resolved correctly""" % (elp_name, num))
                            doomed = True
    except IOError:
        # If _CoqProject is missing, then use Coqoon's default settings
        configuration = [["SourceLoadPath", str(cwd.append("src"))],
                         ["DefaultOutput", str(cwd.append("bin"))],
                         ["AbstractLoadPath",
                          "dk.itu.sdg.kopitiam/lp/coq/8.4"]]
    return (default_output, configuration)

# Read this project's configuration
default_output, configuration = \
    load_coq_project_configuration(None, "_CoqProject")

# This script can only support abstract load paths with some help from Coqoon,
# which produces a "configure.coqoon.vars" file specifying incomplete paths to
# the Coq load path entries that are associated with the abstract load paths
# required by this project

def load_vars(path):
    vs = []
    try:
        tokens = []
        with io.open(path, mode = "r", encoding = "utf_8") as file:
            tokens = shlex.split(file.read(), comments = True)
        while len(tokens) != 0:
            v = None
            if tokens[0] == "var":
                (v, tokens) = (tokens[0:3], tokens[3:])
            elif tokens[0] == "alp":
                if tokens[2] == "name":
                    (v, tokens) = (tokens[0:4], tokens[4:])
                elif (tokens[2] == "include" or
                     tokens[2] == "include-recursive"):
                    (v, tokens) = (tokens[0:5], tokens[5:])
                else:
                    tokens = tokens[1:]
            else:
                # Skip this token in the hope that we'll eventually get back in
                # sync
                tokens = tokens[1:]
            if v != None:
                vs.append(v)
    except IOError:
        pass
    return vs

def structure_vars(vs):
    expected_vars = {} # Variable name -> human-readable description of
                       # variable
    alp_names = {} # Abstract load path ID -> human-readable name
    alp_dirs_with_vars = [] # sequence of (abstract load path ID, directory,
                            # coqdir, recursive)
    for i in vs:
        if i[0] == "var":
            expected_vars[i[1]] = i[2]
        elif i[0] == "alp":
            aid = i[1]
            if i[2] == "name":
                alp_names[aid] = i[3]
            elif i[2] == "include":
                alp_dirs_with_vars.append((aid, i[3], i[4], False))
            elif i[2] == "include-recursive":
                alp_dirs_with_vars.append((aid, i[3], i[4], True))
    return (expected_vars, alp_names, alp_dirs_with_vars)

vs = load_vars("configure.coqoon.vars")
if len(vs) == 0:
    warn("""\
the "configure.coqoon.vars" file is missing, empty, or unreadable; \
non-trivial dependency resolution may fail""")

def substitute_variables(expected_vars, alp_names, alp_dirs_with_vars):
    for vn in expected_vars:
        val = prompt_for(vn, "Specify a value for \"%s\"." % expected_vars[vn])
        if val != None:
            variables[vn] = val

        if not vn in variables:
            affected_alps = []
            for aid, directory, _, _ in alp_dirs_with_vars:
                name = "\"%s\"" % alp_names.get(aid, aid)
                if ("$(%s)" % vn) in directory and not name in affected_alps:
                    affected_alps.append(name)
            aalps = None
            if len(affected_alps) == 1:
                aalps = affected_alps[0]
            elif len(affected_alps) > 1:
                aalps = ", ".join(affected_alps[0:-1]) + " and " + affected_alps[-1]
            warn("""\
the variable %s is not defined; dependencies on %s will not be resolved \
correctly""" % (vn, aalps))
    alp_dirs = {} # Abstract load path ID -> sequence of (possibly resolved
                  # directory, coqdir, recursive)
    for aid, directory, coqdir, recursive in alp_dirs_with_vars:
        for vn, vv in variables.items():
            directory = directory.replace("$(%s)" % vn, vv)
        alp_elements = alp_dirs.get(aid, [])
        alp_elements.append((directory, coqdir, recursive))
        alp_dirs[aid] = alp_elements
    return alp_dirs

alp_directories = substitute_variables(*structure_vars(vs))

# Find all source directories and their corresponding output directories
source_directories = [] # sequence of (source directory, output directory)
for i in configuration:
    if i[0] == "SourceLoadPath":
        entry = (i[1], i[2] if len(i) > 2 else default_output)
        source_directories.append(entry)

def extract_dependency_identifiers(f):
    identifiers = []
    for line in iter(f.readline, ""):
        for (_, ids) in re.findall("(?s)Require\\s+(Import\\s+|Export\\s+|)(.*?)\\s*\\.[$\\s]", line, 0):
            # Using shlex.split here is /technically/ cheating, but it means we
            # can handle both quoted identifiers and multiple identifiers with
            # the same code
            identifiers.extend(shlex.split(ids))
    return identifiers

def is_name_valid(name):
    # Keep this in sync with CoqCompiler.scala
    for c in name:
        if c.isspace() or c == '.':
            return False
    return True

# Populate the dependency map with the basics: .vo files depend on their source
deps = {} # Target path -> sequence of dependency paths
to_be_resolved = {}
for srcdir, bindir in source_directories:
    srcroot = Path(srcdir)
    binroot = Path(bindir)
    for current, dirs, files in os.walk(srcdir):
        curbase = os.path.basename(current)
        if not is_name_valid(curbase):
            continue
        srcpath = Path(current)
        binpath = binroot.append_path(srcpath.drop_first(len(srcroot)))
        if not binpath.isdir():
            # Although the Makefile will be able to create this folder, the
            # load path expansion code needs it to exist in order to work
            # properly -- so, like Coqoon, we create it in advance
            os.makedirs(str(binpath))
        for sf_ in filter(lambda f: f.endswith(".v"), files):
            sf = srcpath.append(sf_)
            bf = binpath.append(sf_ + "o")
            deps[str(bf)] = [str(sf)]

            # While we're here, stash away the identifiers of the dependencies
            # of this source file
            ids = None
            with sf.open() as file:
                ids = extract_dependency_identifiers(file)
            to_be_resolved[(str(sf), str(bf))] = ids

def expand_load_path(alp_dirs, configuration):
    def expand_pair(coqdir, directory):
        if not os.path.isdir(directory):
            warn("couldn't find directory \"%s\"" % directory)
            return []
        expansion = []
        base = Path(directory)
        for current, _, _ in os.walk(directory):
            curbase = os.path.basename(current)
            if not is_name_valid(curbase):
                continue
            relative = Path(current).drop_first(len(base))
            sub = ".".join(relative)
            full = None
            if len(coqdir) == 0:
                full = sub
            elif len(sub) == 0:
                full = coqdir
            else:
                full = "%s.%s" % (coqdir, sub)
            expansion.append((full, current))
        return expansion
    load_path = []
    for i in configuration:
        if i[0] == "SourceLoadPath":
            s, b = (i[1], i[2] if len(i) > 2 else default_output)
            load_path.extend(expand_pair("", s))
            load_path.extend(expand_pair("", b))
        elif i[0] == "DefaultOutput":
            load_path.extend(expand_pair("", i[1]))
        elif i[0] == "ExternalLoadPath":
            directory = i[1]
            coqdir = i[2] if len(i) > 2 else ""
            load_path.extend(expand_pair(coqdir, directory))
        elif i[0] == "AbstractLoadPath":
            alp_elements = alp_dirs.get(i[1])
            if alp_elements != None:
                for d, cd, r in alp_elements:
                    if not os.path.isdir(d):
                        # Unresolved directory; skip it
                        warn("couldn't find directory \"%s\"" % d)
                        continue
                    elif not r:
                        load_path.append((cd, d))
                    else:
                        load_path.extend(expand_pair(cd, d))
        elif i[0] == "ProjectLoadPath":
            pn = i[1]
            pn_var = "%s_PROJECT_PATH" % pn.upper()

            path = None
            if pn_var in variables:
                path = Path(variables[pn_var])
            elif "WORKSPACE" in variables:
                path = Path(variables["WORKSPACE"]).append(pn)
            if path != None and path.isdir():
                _, cfg = load_coq_project_configuration(path, str(path.append("_CoqProject")))
                ads = substitute_variables(*structure_vars(load_vars(str(path.append("configure.coqoon.vars")))))
                load_path.extend(expand_load_path(ads, cfg))
            else:
                warning = """\
the project "%s" could not be found; dependencies on it will not be resolved \
correctly""" % pn
                if path == None:
                    warning += """ \
(either specify its path with the %s variable or specify the path to its \
parent directory with the WORKSPACE variable)""" % (pn_var)
                warn(warning)
    return load_path

complete_load_path = expand_load_path( \
    alp_directories, configuration) # sequence of (coqdir, resolved directory)

# Now that we know the names of all the .vo files we're going to create, we
# can use those -- along with the Coq load path -- to calculate the rest of the
# dependencies
for (sf, bf), identifiers in to_be_resolved.iteritems():
    for ident in identifiers:
        (libdir, _, libname) = ident.rpartition(".")
        for coqdir, location in complete_load_path:
            adjusted = libdir if not libdir.startswith(coqdir) \
                else libdir[len(coqdir):]
            p = Path(location)
            for i in adjusted.split("."):
                p = p.append(i)
            p = p.append(libname + ".vo")
            success = False
            try:
                os.stat(str(p))
                success = True
            except:
                success = str(p) in deps
            if success:
                deps[bf].append(str(p))
                break
        else:
            warn("%s: couldn't resolve dependency \"%s\"" % (str(sf), ident))
            doomed = True

if doomed:
    if not args.persevere:
        err("dependency resolution failed, aborting")
    else:
        warn("""\
dependency resolution failed, but continuing anyway as you requested""")

try:
    from socket import gethostname
    from email.Utils import formatdate
    with io.open("Makefile", mode = "w", encoding = "utf_8") as file:
        file.write(u"""\
# Generated by configure.coqoon v%d on "%s"
# at %s.
#
# This Makefile was automatically generated; any local changes you may make
# will not be preserved when it is next regenerated.

COQC = coqc
COQFLAGS = -noglob
override _COQCMD = \\
	mkdir -p "`dirname "$@"`" && $(COQC) $(COQFLAGS) "$<" && mv "$<o" "$@"

""" % (_configure_coqoon_version, gethostname(), formatdate(localtime = True)))

        output_so_far = []
        # Coqoon itself actually passes -R options instead of -I ones, but this
        # works too (and we've already generated these paths for the dependency
        # resolver)
        for coqdir, location in complete_load_path:
            # There's no sense in generating the same flags over and over
            # again! (In particular, most projects will depend on the Coq
            # standard library, and we don't want to have multiple copies of
            # /that/ mass of flags...)
            if not (coqdir, location) in output_so_far:
                output_so_far.append((coqdir, location))
                if not args.use_q:
                    file.write(u"""\
override COQFLAGS += -I "%s" -as "%s"
""" % (location, coqdir))
                else:
                    file.write(u"""\
override COQFLAGS += -Q "%s" "%s"
""" % (location, coqdir))

        for srcdir, bindir in source_directories:
            file.write(u"""\
%s/%%.vo: %s/%%.v
	$(_COQCMD)

""" % (bindir, srcdir))

        file.write(u"""\
OBJECTS = \\
	%s

all: $(OBJECTS)
clean:
	rm -f $(OBJECTS)

""" % " \\\n\t".join([b for b, _ in deps.iteritems()]))

        for f, d in deps.iteritems():
            file.write(u"%s: %s\n" % (f, " ".join(d)))
except IOError as e:
    print e
    pass
