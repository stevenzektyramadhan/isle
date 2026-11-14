fn main() {
    #[cfg(windows)]
    {
        println!("cargo:rerun-if-changed=assets/isle.ico");
        let mut resource = winresource::WindowsResource::new();
        resource.set_icon("assets/isle.ico");
        resource.set("ProductName", "Isle");
        resource.set("FileDescription", "Isle audio player");
        resource.set("LegalCopyright", "MIT License");
        resource
            .compile()
            .expect("failed to compile Windows resources");
    }
}
