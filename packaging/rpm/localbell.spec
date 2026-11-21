%global debug_package %{nil}

Name:           localbell
Version:        %{pkg_version}
Release:        1%{?dist}
Summary:        Android notification forwarder for Linux desktop

License:        AGPL-3.0-only
URL:            https://github.com/fredjunior54/localbell
Source0:        %{name}-%{version}.tar.gz

%description
HTTP server that receives notifications from Android devices and displays
them on Linux desktop via D-Bus notifications.

%prep
%autosetup

%install
install -Dm755 bin/localbell %{buildroot}%{_bindir}/localbell

%files
%license LICENSE
%{_bindir}/localbell

%changelog
* Wed Dec 10 2025 Alessandro Lattao <alessandro@lattao.com>
- Initial package
